/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.tcp.TcpConnector;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
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
        CustomTransport.prepared.set(0);
        CustomProtocol.configurations.clear();
    }

    @Test
    public void customTransportAndProtocolComposeLazilyThroughContextClassLoader() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { CustomProtocol.class });
        ConnectorConfig config = config("CUSTOM-PROTOCOL");
        ProtocolConfig mount = config.protocol("custom");
        try (ConnectorManager manager = this.manager(loader, config); //
             ServerSocket server = new ServerSocket(0)) {
            // 准备配置不创建网络资源，后续连接和监听使用同一个配置对象。
            manager.init();
            assertEquals(0, CustomTransport.created.get());
            assertEquals(1, CustomTransport.prepared.get());
            assertSame(mount, config.protocol("custom"));
            assertEquals("ready", mount.option("preparation", null));
            assertEquals("Java", mount.option("serializeType", null));
            InterAddress target = new InterAddress("custom", "127.0.0.1", server.getLocalPort(), "test");
            MetChannel channel = manager.connect(target).get(3, TimeUnit.SECONDS);
            assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                RequestPayload request = new RequestPayload();
                request.setRequestID(0);
                channel.sendData(request).get(3, TimeUnit.SECONDS);
                assertEquals(37, peer.getInputStream().read());
                MetListen listen = manager.bind("test").get(3, TimeUnit.SECONDS);
                assertTrue(listen.isActive());
                assertTrue(manager.find("test") instanceof EndpointConnector);
                assertEquals(1, CustomTransport.created.get());
                channel.close().get(3, TimeUnit.SECONDS);

                // 同一端点收到入站连接时也复用配置，不再次规范化或复制。
                try (Socket incoming = new Socket("127.0.0.1", listen.getBindAddress().getPort())) {
                    assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
                    assertEquals(1, CustomTransport.prepared.get());
                }
            }
        }
    }

    @Test
    public void protocolMissingFromContextLoaderIsRejectedWithoutAllocatingTransport() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[0]);
        this.assertPreparationFailure(loader, config("custom-protocol"), "Unknown protocol");
    }

    @Test
    public void incompatibleTransportIsRejectedBeforeCreatingNetworkResources() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { EndpointRoutingTest.HttpA.class });
        this.assertPreparationFailure(loader, config("httpa"), "requires transport http");
    }

    @Test
    public void incompatibleMessageContractIsRejectedBeforeCreatingNetworkResources() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class }, new Class<?>[] { WrongMessageProtocol.class });
        this.assertPreparationFailure(loader, config("wrong-message"), "message type");
    }

    @Test
    public void transportNamesMustBeUniqueIgnoringCase() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[] { CustomTransport.class, DuplicateTransport.class }, null);
        this.assertPreparationFailure(loader, config("custom-protocol"), "Duplicate transport SPI name");
    }

    @Test
    public void protocolNamesMustBeUniqueIgnoringCase() throws Exception {
        ClassLoader loader = this.loader(null, new Class<?>[] { CustomProtocol.class, DuplicateProtocol.class });
        this.assertPreparationFailure(loader, config("custom-protocol"), "Duplicate protocol SPI name");
    }

    @Test
    public void blankProtocolNameIsRejectedDuringDiscovery() throws Exception {
        ClassLoader loader = this.loader(null, new Class<?>[] { BlankProtocol.class });
        this.assertPreparationFailure(loader, config("custom-protocol"), "Missing protocol SPI name");
    }

    @Test
    public void missingTransportIsRejectedDuringPreparation() throws Exception {
        ClassLoader loader = this.loader(new Class<?>[0], new Class<?>[] { CustomProtocol.class });
        this.assertPreparationFailure(loader, config("custom-protocol"), "Unknown transport: custom");
    }

    private void assertPreparationFailure( //
            ClassLoader loader, //
            ConnectorConfig config, //
            String expected) throws Exception {
        try (ConnectorManager manager = this.manager(loader, config)) {
            fail("Invalid configuration must fail before creating network resources");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            assertTrue(failure.toString(), failure.getMessage().contains(expected));
        }
        assertEquals(0, CustomTransport.created.get());
    }

    private static ConnectorConfig config(String protocol) throws IOException {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        InterAddress localAddress = new InterAddress("custom", "127.0.0.1", port, "test");
        ProtocolConfig mount = new ProtocolConfig("custom", protocol, Collections.emptyMap());
        Map<String, String> options = Map.of("listenType", "custom", "workerThread", "1");
        List<ProtocolConfig> mounts = Collections.singletonList(mount);
        return new ConnectorConfig("test", localAddress, options, mounts, true);
    }

    private ConnectorManager manager(ClassLoader loader, ConnectorConfig config) {
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getConnectorConfigs")) {
                return Collections.singletonList(config);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, new Class<?>[] { MetSettings.class }, settingsHandler);
        InvocationHandler contextHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getSettings")) {
                return settings;
            }
            if (StringUtils.equals(method.getName(), "getClassLoader")) {
                return loader;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, contextHandler);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(loader);
        factory.prepare(config);
        return new ConnectorManager(context, factory);
    }

    private ClassLoader loader(Class<?>[] transports, Class<?>[] protocols) throws Exception {
        Map<String, URL> descriptors = new HashMap<>();
        this.descriptor(descriptors, NetworkConnectorFactory.class, transports);
        this.descriptor(descriptors, ProtocolFactory.class, protocols);
        return new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                URL descriptor = descriptors.get(name);
                if (descriptor == null) {
                    return super.getResources(name);
                }
                return Collections.enumeration(Collections.singletonList(descriptor));
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
        private static final AtomicInteger created  = new AtomicInteger();
        private static final AtomicInteger prepared = new AtomicInteger();

        public void configRoutes(Map<String, Map<String, String>> routes) {
            prepared.incrementAndGet();
            for (Map<String, String> options : routes.values()) {
                options.put("preparation", "ready");
            }
        }

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
        private static final BlockingQueue<ProtocolConfig> configurations = new LinkedBlockingQueue<>();

        public ProtocolSession<byte[]> create( //
                ProtocolConfig config, //
                ProtocolContext context, //
                NetworkChannel<byte[]> connection, //
                BiConsumer<Long, Payload> messages) {
            configurations.add(config);
            return super.create(config, context, connection, messages);
        }

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
