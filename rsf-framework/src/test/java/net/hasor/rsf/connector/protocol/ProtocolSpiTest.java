/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.transport.NetworkConnector;
import net.hasor.rsf.connector.transport.NetworkConnectorFactory;
import net.hasor.rsf.connector.transport.tcp.TcpConnector;
import net.hasor.rsf.domain.payload.RequestPayload;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Discover extensions from the context loader and exercise normal Manager entrypoints. */
public class ProtocolSpiTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Before
    public void reset() {
        CustomTransport.created.set(0);
    }

    @Test
    public void customTransportAndProtocolComposeLazilyThroughContextClassLoader() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { CustomProtocol.class });
        try (ConnectorManager manager = this.manager(loader, config("CUSTOM-PROTOCOL")); ServerSocket server = new ServerSocket(0)) {
            manager.init();
            assertEquals(0, CustomTransport.created.get());
            RsfChannel channel = manager.connect(new InterAddress("custom", "127.0.0.1", server.getLocalPort(), "test")).get(3, TimeUnit.SECONDS);
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                RequestPayload request = new RequestPayload();
                request.setRequestID(0);
                channel.sendData(request).get(3, TimeUnit.SECONDS);
                assertEquals(37, peer.getInputStream().read());
                RsfListen listen = manager.bind("test").get(3, TimeUnit.SECONDS);
                assertTrue(listen.isActive());
                assertTrue(manager.find("test") instanceof EndpointConnector);
                assertEquals(1, CustomTransport.created.get());
                channel.close().get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void protocolMissingFromContextLoaderIsRejectedWithoutAllocatingTransport() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[0]);
        this.assertBindFailure(loader, config("custom-protocol"), "Unknown protocol");
    }

    @Test
    public void incompatibleTransportIsRejectedBeforeCreatingNetworkResources() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { EndpointRoutingTest.HttpA.class });
        this.assertBindFailure(loader, config("httpa"), "requires transport http");
    }

    @Test
    public void incompatibleMessageContractIsRejectedBeforeCreatingNetworkResources() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { WrongMessageProtocol.class });
        this.assertBindFailure(loader, config("wrong-message"), "message type");
    }

    @Test
    public void transportNamesMustBeUniqueIgnoringCase() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class, DuplicateTransport.class }, null);
        this.assertInitFailure(loader, "Duplicate transport SPI name");
    }

    @Test
    public void protocolNamesMustBeUniqueIgnoringCase() throws Exception {
        ClassLoader loader = this.loader(null, new Class<?>[] { CustomProtocol.class, DuplicateProtocol.class });
        this.assertInitFailure(loader, "Duplicate protocol SPI name");
    }

    @Test
    public void blankProtocolNameIsRejectedDuringDiscovery() throws Exception {
        ClassLoader loader = this.loader(null, new Class<?>[] { BlankProtocol.class });
        this.assertInitFailure(loader, "Missing protocol SPI name");
    }

    @Test
    public void missingTransportIsRejectedDuringInitialization() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[0], new Class<?>[] { CustomProtocol.class });
        this.assertInitFailure(loader, "No connector factory for custom");
    }

    private void assertBindFailure(ClassLoader loader, ConnectorConfig config, String expected) throws Exception {
        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            try {
                manager.bind("test").get(3, TimeUnit.SECONDS);
                fail("Invalid assembly must fail before listening");
            } catch (ExecutionException failure) {
                assertTrue(failure.getCause().toString(), failure.getCause().getMessage().contains(expected));
            }
            assertNull(manager.find("test"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    private void assertInitFailure(ClassLoader loader, String expected) throws Exception {
        try (ConnectorManager manager = this.manager(loader, config("custom-protocol"))) {
            try {
                manager.init();
                fail("Invalid SPI registry must fail initialization");
            } catch (IllegalArgumentException | IllegalStateException failure) {
                assertTrue(failure.toString(), failure.getMessage().contains(expected));
            }
            assertEquals(0, CustomTransport.created.get());
        }
    }

    private static ConnectorConfig config(String protocol) throws IOException {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        return new ConnectorConfig("test", new InterAddress("custom", "127.0.0.1", port, "test"), Map.of("listenType", "custom", "workerThread", "1"), Collections.singletonList(new ProtocolConfig("custom", protocol, Collections.emptyMap())), true);
    }

    private ConnectorManager manager(ClassLoader loader, ConnectorConfig config) {
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if (method.getName().equals("getConnectorConfigs")) {
                return Collections.singletonList(config);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        RsfContext context = (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            if (method.getName().equals("getSettings")) {
                return settings;
            }
            if (method.getName().equals("getClassLoader")) {
                return loader;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return new ConnectorManager(context, new EndpointConnectorFactory());
    }

    private ClassLoader loader(Class<?>[] transports, Class<?>[] protocols) throws Exception {
        Map<String, URL> descriptors = new HashMap<>();
        this.descriptor(descriptors, NetworkConnectorFactory.class, transports);
        this.descriptor(descriptors, ProtocolFactory.class, protocols);
        return new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                URL descriptor = descriptors.get(name);
                return descriptor == null ? super.getResources(name) : Collections.enumeration(Collections.singletonList(descriptor));
            }
        };
    }

    private void descriptor(Map<String, URL> descriptors, Class<?> service, Class<?>[] providers) throws Exception {
        if (providers == null) {
            return;
        }
        Path descriptor = this.temporary.newFile().toPath();
        Files.write(descriptor, Arrays.stream(providers).map(Class::getName).toList());
        descriptors.put("META-INF/services/" + service.getName(), descriptor.toUri().toURL());
    }

    public static class CustomTransport implements NetworkConnectorFactory<byte[]> {
        private static final AtomicInteger created = new AtomicInteger();

        public String name() {
            return "CUSTOM";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public NetworkConnector<byte[]> create(ConnectorConfig config, ClassLoader loader) {
            created.incrementAndGet();
            return new TcpConnector(config, loader);
        }
    }

    public static class DuplicateTransport extends CustomTransport {
        public String name() {
            return "custom";
        }
    }

    public static class CustomProtocol extends SocketLifecycleTest.RawProtocol {
        public String name() {
            return "CUSTOM-PROTOCOL";
        }

        public String transport() {
            return "custom";
        }
    }

    public static class DuplicateProtocol extends CustomProtocol {
        public String name() {
            return "custom-protocol";
        }
    }

    public static class BlankProtocol extends CustomProtocol {
        public String name() {
            return " ";
        }
    }

    public static class WrongMessageProtocol extends HttpTransportTest.TextProtocol {
        public String name() {
            return "wrong-message";
        }

        public String transport() {
            return "custom";
        }
    }
}
