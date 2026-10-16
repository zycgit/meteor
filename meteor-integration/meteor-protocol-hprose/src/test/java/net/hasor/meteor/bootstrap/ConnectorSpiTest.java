/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.io.IOException;
import java.util.*;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.payload.Payload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorSpiTest {
    @Test
    public void connectorSpecificConfigurationIsPassedWithoutFrameworkChanges() throws Exception {
        // 自定义传输和协议均通过测试 SPI 注册，参数从正式配置传递。
        MetConfiguration settings = new MetConfiguration();
        MetConfiguration.ConnectorSettings custom = settings.connector("custom");
        custom.bind("127.0.0.1", 23000);
        custom.option("custom.nested", "custom-value");
        custom.option("custom.parallelism", 3);
        custom.protocol("custom-test").addressScheme("custom-wire");
        try (MetContext runtime = settings.buildContext()) {
            assertNull(runtime.getListenAddress("custom-test"));

            // 启动后才创建监听，地址 scheme 使用挂载声明的别名。
            runtime.start();
            assertEquals(Collections.singleton("custom-test"), runtime.runProtocols());
            assertEquals(23000, runtime.getListenAddress("custom-test").getPort());
            assertEquals("custom-wire", runtime.getListenAddress("custom-test").getSchema());
        }
    }

    @Test
    public void assemblyClosesAllConnectorsAfterPartialBindAndPreservesCause() throws Exception {
        // 第二个监听故障不能留下第一个监听及任意已创建连接器。
        FailingTransport.events.clear();
        FailingTransport.bindings = 0;
        FailingTransport.firstListen = null;
        MetConfiguration settings = new MetConfiguration();
        settings.connector("failing") //
                .bind("127.0.0.1", 2181) //
                .protocol("failing-test");
        settings.connector("failing") //
                .bind("127.0.0.1", 2182) //
                .protocol("failing-second");
        try (MetContext runtime = settings.buildContext()) {
            // 第二次 bind 失败后，构建层保留原始原因并撤销在线状态。
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);
            assertSame(FailingTransport.failure, failure.getCause());
            assertFalse(runtime.isOnline());
            assertTrue(runtime.runProtocols().isEmpty());

            // 两个连接器均应关闭，已成功创建的第一个监听也必须回收。
            assertEquals(4, FailingTransport.events.size());
            String first = FailingTransport.events.get(0).substring("bind:".length());
            String second = FailingTransport.events.get(1).substring("bind:".length());
            List<String> expectedBindings = Arrays.asList("bind:" + first, "bind:" + second);
            assertEquals(expectedBindings, FailingTransport.events.subList(0, 2));
            Set<String> closed = new HashSet<>(FailingTransport.events.subList(2, 4));
            assertEquals(Set.of("close:" + first, "close:" + second), closed);
            assertNotNull(FailingTransport.firstListen);
            assertFalse(FailingTransport.firstListen.isOpen());
        }

        // 外层再次关闭上下文不能重复关闭已回收的连接器。
        assertEquals(4, FailingTransport.events.size());
    }

    public static class CustomTransport implements TransportFactory<byte[]> {
        public boolean sharedRoutes() {
            return false;
        }

        public void configRoutes(Map<String, Map<String, String>> routes) {
        }

        public String name() {
            return "custom";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            return TransportPlan.direct(config, (prepared, runtime) -> {
                // 验证组装器把协议专属参数原样传给传输提供者。
                assertEquals("custom-value", prepared.option("custom.nested", null));
                assertEquals(3, prepared.integer("custom.parallelism", 1));
                return new StubConnector();
            });
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

        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            return TransportPlan.direct(config, (prepared, runtime) -> {
                // 只在第二次绑定注入故障，并记录关闭动作供外层验证清理范围。
                return new StubConnector() {
                    public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                            ChannelListenerFactory<byte[]> factory) throws IOException {
                        events.add("bind:" + prepared.name());
                        if (++bindings == 2) {
                            throw failure;
                        }
                        firstListen = super.bind(address, routes, factory);
                        return firstListen;
                    }

                    public void close() {
                        events.add("close:" + prepared.name());
                    }
                };
            });
        }
    }

    private static class StubConnector implements NetworkConnector<byte[]> {
        public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                ChannelListenerFactory<byte[]> factory) throws IOException {
            // 测试监听只模拟开放状态和一次性关闭通知，不创建实际网络资源。
            return new NetworkListen() {
                private boolean  open = true;
                private boolean  suspended;
                private Runnable closed;

                public InterAddress getAddress() {
                    return address;
                }

                public boolean isOpen() {
                    return this.open && !this.suspended;
                }

                public void onClose(Runnable action) {
                    this.closed = action;
                }

                public void suspend() {
                    this.suspended = true;
                }

                public void resume() {
                    if (!this.open) {
                        throw new IllegalStateException("Listener is closed");
                    }
                    this.suspended = false;
                }

                public void close() {
                    // 首次关闭触发回调，重复关闭保持幂等。
                    if (this.open) {
                        this.open = false;
                        if (this.closed != null) {
                            this.closed.run();
                        }
                    }
                }
            };
        }

        public Future<Void> connect(InterAddress address, RouteTable<byte[]> routes, String initialMountId, //
                ChannelListenerFactory<byte[]> factory) {
            throw new UnsupportedOperationException();
        }

        public void close() {
        }
    }

    public static class CustomProtocol implements ProtocolFactory<byte[]> {
        @Override
        public RouteProbeResult probe(ProtocolConfig config, byte[] message) {
            return RouteProbeResult.REJECT;
        }

        public String name() {
            return "custom-test";
        }

        public String defaultSerializeType() {
            return "Java";
        }

        public String transportType() {
            return "custom";
        }

        @Override
        public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
                NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
            throw new AssertionError("Binding must not open a protocol session");
        }
    }

    public static class FailingProtocol extends CustomProtocol {
        public String name() {
            return "failing-test";
        }

        public String transportType() {
            return "failing";
        }
    }

    public static final class SecondFailingProtocol extends FailingProtocol {
        public String name() {
            return "failing-second";
        }
    }
}
