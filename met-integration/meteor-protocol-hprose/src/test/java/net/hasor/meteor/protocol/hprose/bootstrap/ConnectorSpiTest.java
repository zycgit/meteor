/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.Configuration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.payload.Payload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorSpiTest {
    @Test
    public void connectorSpecificConfigurationIsPassedWithoutFrameworkChanges() throws Exception {
        Configuration settings = new Configuration();
        settings.connector("custom").bind("127.0.0.1", 23000).option("custom.nested", "custom-value").option("custom.parallelism", 3).protocol("custom-test");
        try (MetContext runtime = settings.buildContext()) {
            assertNull(runtime.bindAddress("custom-test"));
            runtime.start();
            assertEquals(Collections.singleton("custom-test"), runtime.runProtocols());
            assertEquals(23000, runtime.bindAddress("custom-test").getPort());
            assertEquals("custom-wire", runtime.bindAddress("custom-test").getSchema());
        }
    }

    @Test
    public void assemblyClosesAllConnectorsAfterPartialBindAndPreservesCause() throws Exception {
        FailingTransport.events.clear();
        FailingTransport.bindings = 0;
        FailingTransport.firstListen = null;
        Configuration settings = new Configuration();
        settings.connector("failing").bind("127.0.0.1", 2181).protocol("failing-test");
        settings.connector("failing").bind("127.0.0.1", 2182).protocol("failing-second");
        try (MetContext runtime = settings.buildContext()) {
            try {
                runtime.start();
                fail("Second listener must fail startup");
            } catch (IllegalStateException expected) {
                assertSame(FailingTransport.failure, expected.getCause());
            }
            assertFalse(runtime.isOnline());
            assertTrue(runtime.runProtocols().isEmpty());
            assertEquals(4, FailingTransport.events.size());
            String first = FailingTransport.events.get(0).substring("bind:".length());
            String second = FailingTransport.events.get(1).substring("bind:".length());
            assertEquals(Arrays.asList("bind:" + first, "bind:" + second), FailingTransport.events.subList(0, 2));
            assertEquals(new HashSet<>(Arrays.asList("close:" + first, "close:" + second)), new HashSet<>(FailingTransport.events.subList(2, 4)));
            assertNotNull(FailingTransport.firstListen);
            assertFalse(FailingTransport.firstListen.isOpen());
        }
        assertEquals(4, FailingTransport.events.size());
    }

    public static class CustomTransport implements NetworkConnectorFactory<byte[]> {
        public String name() {
            return "custom";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public NetworkConnector<byte[]> create(ConnectorConfig config, ClassLoader loader) {
            // Plugin options reach the actual transport without changing the assembler.
            assertEquals("custom-value", config.option("custom.nested", null));
            assertEquals(3, config.integer("custom.parallelism", 1));
            return new StubConnector();
        }
    }

    public static final class FailingTransport extends CustomTransport {
        private static final List<String>  events  = new ArrayList<>();
        private static final IOException   failure = new IOException("second bind failed");
        private static       int           bindings;
        private static       NetworkListen firstListen;

        public String name() {
            return "failing";
        }

        public NetworkConnector<byte[]> create(ConnectorConfig config, ClassLoader loader) {
            return new StubConnector() {
                public NetworkListen bind(InterAddress address, ChannelFactory<byte[]> factory) throws IOException {
                    events.add("bind:" + config.name());
                    if (++bindings == 2) {
                        throw failure;
                    }
                    firstListen = super.bind(address, factory);
                    return firstListen;
                }

                public void close() {
                    events.add("close:" + config.name());
                }
            };
        }
    }

    private static class StubConnector implements NetworkConnector<byte[]> {
        public NetworkListen bind(InterAddress address, ChannelFactory<byte[]> factory) throws IOException {
            return new NetworkListen() {
                private boolean  open = true;
                private Runnable closed;

                public InterAddress getAddress() {
                    return address;
                }

                public boolean isOpen() {
                    return this.open;
                }

                public void onClose(Runnable action) {
                    this.closed = action;
                }

                public void close() {
                    if (this.open) {
                        this.open = false;
                        if (this.closed != null) {
                            this.closed.run();
                        }
                    }
                }
            };
        }

        public Future<Void> connect(InterAddress address, ChannelFactory<byte[]> factory) {
            throw new UnsupportedOperationException();
        }

        public void close() {
        }
    }

    public static class CustomProtocol implements ProtocolFactory<byte[]> {
        @Override
        public RouteMatch probe(ProtocolConfig config, byte[] message) {
            return RouteMatch.REJECT;
        }

        public String name() {
            return "custom-test";
        }

        public String scheme() {
            return "custom-wire";
        }

        public String transport() {
            return "custom";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public ProtocolSession<byte[]> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
            throw new AssertionError("Binding must not open a protocol session");
        }
    }

    public static class FailingProtocol extends CustomProtocol {
        public String name() {
            return "failing-test";
        }

        public String scheme() {
            return this.name();
        }

        public String transport() {
            return "failing";
        }
    }

    public static final class SecondFailingProtocol extends FailingProtocol {
        public String name() {
            return "failing-second";
        }
    }
}
