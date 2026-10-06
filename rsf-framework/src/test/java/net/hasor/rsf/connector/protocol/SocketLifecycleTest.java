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
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the public connector lifecycle against physical sockets and a byte-only test protocol. */
public class SocketLifecycleTest {
    private static final int WRITE_SIZE = 8 * 1024 * 1024;

    @Test(timeout = 15000)
    public void cancelledSendStillDrainsItsPhysicalWrite() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); ConnectorManager manager = this.manager(listener.getLocalPort())) {
            RsfChannel channel = manager.connect(this.address(listener.getLocalPort())).get(3, TimeUnit.SECONDS);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                Future<RsfChannel> sent = channel.sendData(this.request());
                // Receipt of the first byte proves that the physical write has started.
                assertEquals(37, peer.getInputStream().read());
                assertFalse(sent.isDone());
                assertTrue(sent.cancel());
                Future<RsfChannel> closed = channel.drainAndClose();
                assertFalse(closed.isDone());
                this.assertRejected(channel);
                byte[] buffer = new byte[65536];
                int total = 1;
                int count;
                while ((count = peer.getInputStream().read(buffer)) != -1) {
                    total += count;
                }
                assertEquals(WRITE_SIZE, total);
                assertSame(channel, closed.get(3, TimeUnit.SECONDS));
                assertSame(closed, channel.close());
                assertFalse(channel.isActive());
            }
        }
    }

    @Test(timeout = 10000)
    public void hardCloseInterruptsWriteWithoutWaitingForPeerToRead() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); ConnectorManager manager = this.manager(listener.getLocalPort())) {
            RsfChannel channel = manager.connect(this.address(listener.getLocalPort())).get(3, TimeUnit.SECONDS);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Future<RsfChannel> sent = channel.sendData(this.request());
                assertEquals(37, peer.getInputStream().read());
                assertFalse(sent.isDone());
                Future<RsfChannel> closed = channel.close();
                assertSame(channel, closed.get(3, TimeUnit.SECONDS));
                assertSame(closed, channel.drainAndClose());
                try {
                    sent.get(3, TimeUnit.SECONDS);
                    fail("The unfinished write must fail on hard close");
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
                assertFalse(channel.isActive());
            }
        }
    }

    @Test(timeout = 10000)
    public void closeBindLeavesExistingConnectionsUsable() throws Exception {
        try (ConnectorManager server = this.manager(0); ConnectorManager client = this.manager(0)) {
            ConnectorConfig config = server.configurations().iterator().next();
            RsfListen listen = server.bind(config.name()).get(3, TimeUnit.SECONDS);
            RsfChannel channel = client.connect(listen.getBindAddress()).get(3, TimeUnit.SECONDS);
            RsfConnector connector = server.find(config.name());
            connector.closeBind();
            assertFalse(listen.isActive());
            assertTrue(connector.getListenList().isEmpty());
            assertSame(channel, channel.sendData(new RequestPayload()).get(3, TimeUnit.SECONDS));
            assertTrue(channel.isActive());
            try {
                server.bind(config.name()).get(3, TimeUnit.SECONDS);
                fail("Binding must remain disabled");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        }
    }

    private void assertRejected(RsfChannel channel) throws Exception {
        try {
            channel.sendData(this.request()).get(1, TimeUnit.SECONDS);
            fail("New writes must be rejected during drain");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    private RequestPayload request() {
        RequestPayload request = new RequestPayload();
        request.setRequestID(1);
        request.setClientTimeout(10000);
        return request;
    }

    private InterAddress address(int port) {
        return new InterAddress("raw", "127.0.0.1", port, "default");
    }

    private ConnectorManager manager(int port) throws IOException {
        if (port == 0) {
            try (ServerSocket available = new ServerSocket(0)) {
                port = available.getLocalPort();
            }
        }
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "tcp");
        options.put("workerThread", "1");
        options.put("protocol", "rawprotocol");
        ConnectorConfig config = new ConnectorConfig("raw", this.address(port), options);
        ClassLoader loader = this.getClass().getClassLoader();
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Collections.singletonList(config);
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 10000;
            }
            throw new AssertionError(method);
        });
        RsfContext context = (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            if ("getSettings".equals(method.getName())) {
                return settings;
            }
            if ("getClassLoader".equals(method.getName())) {
                return loader;
            }
            throw new AssertionError(method);
        });
        ConnectorManager manager = new ConnectorManager(context, new EndpointConnectorFactory());
        manager.init();
        return manager;
    }

    public static class RawProtocol implements ProtocolFactory<byte[]> {
        public String transport() {
            return "tcp";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public String name() {
            return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        public ProtocolSession<byte[]> create(ProtocolConfig config, ProtocolContext manager, NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
            return new ProtocolSession<byte[]>() {
                private final BasicFuture<Void> ready = new BasicFuture<>();

                public Future<Void> ready() {
                    return this.ready;
                }

                public void connected() {
                    this.ready.completed(null);
                }

                public void receive(byte[] bytes) {
                }

                public Future<Void> send(Payload message) {
                    byte[] bytes = new byte[((RequestPayload) message).getRequestID() == 0 ? 1 : WRITE_SIZE];
                    bytes[0] = 37;
                    return connection.write(bytes);
                }

                public void prepareDrain() {
                }

                public void closed(Throwable cause) {
                    this.ready.failed(cause);
                }
            };
        }
    }
}
