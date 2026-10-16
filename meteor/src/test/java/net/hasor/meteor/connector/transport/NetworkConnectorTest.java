/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.connector.transport.tcp.TcpTransportFactory;
import net.hasor.meteor.connector.transport.udp.UdpTransportFactory;
import org.junit.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.TransportTestServices.newRuntimeServices;
import static net.hasor.meteor.connector.transport.TransportTestServices.newHttpConnector;
import static org.junit.Assert.*;

/**
 * 通过真实网络验证独立传输 SPI；不包含应用协议或 RPC 场景。
 */
public class NetworkConnectorTest {
    @Test
    public void transportSpiIsAvailableWithoutProtocolModule() throws Exception {
        // 发现内核提供的全部传输 SPI，并校验名称不会冲突。
        Map<String, TransportFactory<?>> factories = new HashMap<>();
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        for (TransportFactory<?> factory : ServiceLoader.load(TransportFactory.class, loader)) {
            assertNull(factories.put(factory.name(), factory));
        }
        // 每种传输的消息类型和创建契约不依赖协议插件。
        assertEquals(Set.of("tcp", "udp", "http"), factories.keySet());
        assertEquals(byte[].class, factories.get("tcp").messageType());
        assertEquals(byte[].class, factories.get("udp").messageType());
        assertEquals(HttpExchange.class, factories.get("http").messageType());
        for (TransportFactory<?> factory : factories.values()) {
            ConnectorConfig config = this.config(factory.name());
            try (NetworkConnector<?> network = this.createConnector(factory, config, runtimeServices)) {
                assertNotNull(network);
            }
        }
    }

    @Test(timeout = 10000)
    public void tcpIsUsableWithoutRsf() throws Exception {
        // 仅配置 TCP 传输，直接验证 SPI 的监听器退出契约。
        ConnectorConfig config = this.config("tcp");
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        TransportFactory<byte[]> factory = new TcpTransportFactory();
        try (NetworkConnector<byte[]> server = this.createConnector(factory, config, runtimeServices); //
             NetworkConnector<byte[]> client = this.createConnector(factory, config, runtimeServices)) {
            // 监听和连接均使用真实套接字，接收端原样回送字节。
            BasicFuture<NetworkChannel<byte[]>> connected = new BasicFuture<>();
            BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
            RouteTable<byte[]> routes = this.routePlan(config);
            NetworkListen listen = server.bind(config.address(), routes, channel -> new ChannelListener<byte[]>() {

                public void connected() {
                }

                public void receive(byte[] bytes) {
                    channel.write(bytes);
                }

                public void closed(Throwable cause) {
                }
            });
            client.connect(listen.getAddress(), routes, this.mountId(config), channel -> new ChannelListener<byte[]>() {

                public void connected() {
                    connected.completed(channel);
                }

                public void receive(byte[] bytes) {
                    received.add(bytes);
                }

                public void closed(Throwable cause) {
                    connected.failed(cause);
                }
            }).get(2, TimeUnit.SECONDS);
            NetworkChannel<byte[]> channel = connected.get(2, TimeUnit.SECONDS);

            // 先确认现有连接已经完成一次收发。
            channel.write(new byte[] { 1, 2, 3 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1, 2, 3 }, received.poll(2, TimeUnit.SECONDS));

            // 关闭监听器后，已经接入的连接仍可继续收发并正常排空。
            listen.close();
            assertFalse(listen.isOpen());
            channel.write(new byte[] { 4, 5 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 4, 5 }, received.poll(2, TimeUnit.SECONDS));
            channel.drainAndClose().get(2, TimeUnit.SECONDS);
            assertFalse(channel.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void tcpListenerFactoryFailureClosesConstructingChannel() throws Exception {
        // 建立真实 TCP 对端，让失败发生在物理连接已经创建、监听器仍在装配的窗口。
        ConnectorConfig config = this.config("tcp");
        AtomicReference<NetworkChannel<byte[]>> exposed = new AtomicReference<>();
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        try (ServerSocket peer = new ServerSocket(0); //
             NetworkConnector<byte[]> client = this.createConnector(//
                     new TcpTransportFactory(), config, runtimeServices)) {
            InterAddress target = new InterAddress("tcp", "127.0.0.1", peer.getLocalPort(), "default");
            RouteTable<byte[]> routes = this.routePlan(config);

            // 工厂看到通道后立即失败，connect Future 必须结束且通道关闭 Future 不能悬挂。
            var connecting = client.connect(target, routes, this.mountId(config), channel -> {
                exposed.set(channel);
                throw new IllegalStateException("listener construction failed");
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
            NetworkChannel<byte[]> channel = exposed.get();
            assertNotNull(channel);
            channel.close().get(3, TimeUnit.SECONDS);
            assertFalse(channel.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void suspendingBetweenFactoryAndConnectedClosesTheAcceptedRoutedSocket() throws Exception {
        // 接收器装配发生在 connected 回调之前，用闩锁固定这段所有权窗口。
        ConnectorConfig config = this.config("tcp");
        CountDownLatch attaching = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean();
        CountDownLatch delivered = new CountDownLatch(1);
        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        ChannelListenerFactory<byte[]> factory = channel -> this.blockingReceiver(//
                attaching, resume, connected, delivered, received);
        RouteTable<byte[]> routes = this.routePlan(config);

        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        try (NetworkConnector<byte[]> server = this.createConnector(//
                new TcpTransportFactory(), config, runtimeServices); //
             NetworkListen listen = server.bind(config.address(), routes, factory); //
             Socket socket = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            socket.setSoTimeout(3000);
            try {
                assertTrue(attaching.await(3, TimeUnit.SECONDS));

                // suspend 先于 connected 赢得所有权移交，必须关闭 raw socket 并抑制迟到回调。
                listen.suspend();
            } finally {
                resume.countDown();
            }
            assertEquals(-1, socket.getInputStream().read());
            assertFalse(connected.get());
            assertEquals(1L, delivered.getCount());
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void closingBetweenFactoryAndConnectedKeepsTheAcceptedRoutedSocket() throws Exception {
        // close 只关闭监听入口；已 accept 的连接即使仍在建立上层接收器，也应继续完成交付。
        ConnectorConfig config = this.config("tcp");
        CountDownLatch attaching = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean();
        CountDownLatch delivered = new CountDownLatch(1);
        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        ChannelListenerFactory<byte[]> factory = channel -> this.blockingReceiver(//
                attaching, resume, connected, delivered, received);
        RouteTable<byte[]> routes = this.routePlan(config);

        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        try (NetworkConnector<byte[]> server = this.createConnector(//
                new TcpTransportFactory(), config, runtimeServices); //
             NetworkListen listen = server.bind(config.address(), routes, factory); //
             Socket socket = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            try {
                assertTrue(attaching.await(3, TimeUnit.SECONDS));

                // close 与 suspend 的连接处置不同，恢复初始化后应完成 connected 回调。
                listen.close();
            } finally {
                resume.countDown();
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertTrue(connected.get());

            // 已交付连接继续接收数据，证明关闭监听没有误关物理通道。
            socket.getOutputStream().write(new byte[] { 7 });
            socket.getOutputStream().flush();
            assertArrayEquals(new byte[] { 7 }, received.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 10000)
    public void udpIsUsableWithoutRsf() throws Exception {
        // 通过 JDK 临时套接字选择 UDP 端口，再向传输 SPI 显式传入非零监听端口。
        int port;
        try (DatagramSocket available = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            port = available.getLocalPort();
        }
        InterAddress address = new InterAddress("udp", "127.0.0.1", port, "default");
        Map<String, String> options = Map.of();
        ProtocolConfig protocol = ProtocolConfig.newConfig("udp", "udp", options);
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        TransportFactory<byte[]> factory = new UdpTransportFactory();
        try (NetworkConnector<byte[]> server = this.createConnector(factory, config, runtimeServices); //
             NetworkConnector<byte[]> client = this.createConnector(factory, config, runtimeServices)) {
            // 监听和连接均使用真实套接字，接收端原样回送字节。
            BasicFuture<NetworkChannel<byte[]>> connected = new BasicFuture<>();
            BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
            RouteTable<byte[]> routes = this.routePlan(config);
            NetworkListen listen = server.bind(config.address(), routes, channel -> new ChannelListener<byte[]>() {

                public void connected() {
                }

                public void receive(byte[] bytes) {
                    channel.write(bytes);
                }

                public void closed(Throwable cause) {
                }
            });
            client.connect(listen.getAddress(), routes, this.mountId(config), channel -> new ChannelListener<byte[]>() {

                public void connected() {
                    connected.completed(channel);
                }

                public void receive(byte[] bytes) {
                    received.add(bytes);
                }

                public void closed(Throwable cause) {
                    connected.failed(cause);
                }
            }).get(2, TimeUnit.SECONDS);
            NetworkChannel<byte[]> channel = connected.get(2, TimeUnit.SECONDS);

            // 先确认现有连接已经完成一次收发。
            channel.write(new byte[] { 1, 2, 3 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1, 2, 3 }, received.poll(2, TimeUnit.SECONDS));

            // 关闭监听器后，已经接入的连接仍可继续收发并正常排空。
            listen.close();
            assertFalse(listen.isOpen());
            channel.write(new byte[] { 4, 5 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 4, 5 }, received.poll(2, TimeUnit.SECONDS));
            channel.drainAndClose().get(2, TimeUnit.SECONDS);
            assertFalse(channel.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void udpListenerFactoryFailureClosesConstructingChannel() throws Exception {
        // UDP 不等待远端应答，直接固定监听器装配失败后的连接终态。
        ConnectorConfig config = this.config("udp");
        AtomicReference<NetworkChannel<byte[]>> exposed = new AtomicReference<>();
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        try (DatagramSocket peer = new DatagramSocket(); //
             NetworkConnector<byte[]> client = this.createConnector(//
                     new UdpTransportFactory(), config, runtimeServices)) {
            InterAddress target = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            RouteTable<byte[]> routes = this.routePlan(config);

            // 工厂失败必须同步反映到连接 Future，并由已经安装的 close 通知结束通道 Future。
            var connecting = client.connect(target, routes, this.mountId(config), channel -> {
                exposed.set(channel);
                throw new IllegalStateException("listener construction failed");
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
            NetworkChannel<byte[]> channel = exposed.get();
            assertNotNull(channel);
            channel.close().get(3, TimeUnit.SECONDS);
            assertFalse(channel.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void udpRejectsZeroBindPortWithoutPreventingValidBind() throws Exception {
        // 构造配置使用合法端口，单独向 bind 传入零端口地址。
        int port;
        try (DatagramSocket available = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            port = available.getLocalPort();
        }
        InterAddress address = new InterAddress("udp", "127.0.0.1", port, "default");
        Map<String, String> options = Map.of();
        ProtocolConfig protocol = ProtocolConfig.newConfig("udp", "udp", options);
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        ClassLoader loader = this.getClass().getClassLoader();
        TransportRuntimeServices runtimeServices = newRuntimeServices(loader);
        try (NetworkConnector<byte[]> server = this.createConnector(//
                new UdpTransportFactory(), config, runtimeServices)) {
            AtomicBoolean peerCreated = new AtomicBoolean();
            ChannelListenerFactory<byte[]> receiver = channel -> {
                peerCreated.set(true);
                throw new AssertionError("Binding alone must not create a peer channel");
            };

            // 校验实际绑定参数，直接拒绝零端口且不创建对端通道。
            InterAddress zeroPort = InterAddress.forBinding("udp", "127.0.0.1", 0, "default");
            RouteTable<byte[]> routes = this.routePlan(config);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
                server.bind(zeroPort, routes, receiver);
            });
            assertEquals("UDP listen port must be between 1 and 65535", error.getMessage());
            assertFalse(peerCreated.get());

            // 参数错误不会破坏连接器，随后可按合法地址正常监听和关闭。
            NetworkListen listen = server.bind(address, routes, receiver);
            try (listen) {
                assertTrue(listen.isOpen());
                assertEquals(address, listen.getAddress());
                assertFalse(peerCreated.get());
            }
            assertFalse(listen.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void httpCarriesCompleteMessagesWithoutRsf() throws Exception {
        // 使用 HTTP 传输 SPI，不安装应用协议。
        ConnectorConfig config = this.config("http");
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader)) {
            BlockingQueue<HttpRequest> requests = new LinkedBlockingQueue<>();
            RouteTable<HttpExchange> routes = this.routePlan(config);
            NetworkListen listen = server.bind(config.address(), routes, //
                    channel -> new ChannelListener<HttpExchange>() {

                        public void connected() {
                        }

                        public void receive(HttpExchange exchange) {
                            requests.add(exchange.request());
                            Map<String, List<String>> headers = Map.of("X-Reply", List.of("received"));
                            HttpResponse response = HttpResponse.newBuffered(//
                                    201, headers, exchange.request().body(), Map.of());
                            exchange.respond(response);
                            channel.write(exchange);
                        }

                        public void closed(Throwable cause) {
                        }
                    });
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), routes, this.mountId(config), //
                    channel -> new ChannelListener<HttpExchange>() {

                        public void connected() {
                            connected.completed(channel);
                        }

                        public void receive(HttpExchange message) {
                            fail("Client responses belong to their exchange");
                        }

                        public void closed(Throwable cause) {
                            connected.failed(cause);
                        }
                    }).get(2, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(2, TimeUnit.SECONDS);

            // 同一个连接连续发送两条独立 HTTP 消息，响应分别归属自己的交换对象。
            Map<String, List<String>> headers = Map.of("X-Test", List.of("one"));
            byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
            HttpRequest firstRequest = HttpRequest.newBuffered("POST", "/plain/first", headers, text, 1, Map.of());
            HttpExchange first = new HttpExchange(firstRequest);
            byte[] bytes = { 8, 9 };
            Map<String, List<String>> noHeaders = Collections.emptyMap();
            HttpRequest secondRequest = HttpRequest.newBuffered("POST", "/plain/second", noHeaders, bytes, 1, Map.of());
            HttpExchange second = new HttpExchange(secondRequest);
            channel.write(first).get(2, TimeUnit.SECONDS);
            channel.write(second).get(2, TimeUnit.SECONDS);
            HttpResponse firstResponse = first.response().get(2, TimeUnit.SECONDS);
            assertEquals(201, firstResponse.status());
            assertEquals("received", firstResponse.header("x-reply"));
            assertArrayEquals(first.request().body(), firstResponse.body());
            HttpResponse secondResponse = second.response().get(2, TimeUnit.SECONDS);
            assertArrayEquals(second.request().body(), secondResponse.body());
            HttpRequest receivedFirst = requests.poll(2, TimeUnit.SECONDS);
            HttpRequest receivedSecond = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(receivedFirst);
            assertNotNull(receivedSecond);
            assertEquals("one", receivedFirst.header("x-test"));
            assertEquals("/plain/second", receivedSecond.uri());
            channel.drainAndClose().get(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 10000)
    public void closingHttpConnectorClosesUnusedLogicalChannel() throws Exception {
        // 使用 HTTP 传输 SPI，不安装应用协议。
        ConnectorConfig config = this.config("http");
        ClassLoader loader = this.getClass().getClassLoader();
        NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try {
            InterAddress unusedRemote = new InterAddress("http", "127.0.0.1", 1, "default");
            RouteTable<HttpExchange> routes = this.routePlan(config);
            client.connect(unusedRemote, routes, this.mountId(config), channel -> new ChannelListener<HttpExchange>() {

                public void connected() {
                    connected.completed(channel);
                }

                public void receive(HttpExchange message) {
                }

                public void closed(Throwable cause) {
                    closed.completed(null);
                }
            }).get(2, TimeUnit.SECONDS);
            // 未发送请求的逻辑通道也属于连接器生命周期。
            assertTrue(connected.get(2, TimeUnit.SECONDS).isOpen());
        } finally {
            client.close();
        }

        // 关闭通知必须到达，并且逻辑通道不可继续使用。
        closed.get(2, TimeUnit.SECONDS);
        assertFalse(connected.get(2, TimeUnit.SECONDS).isOpen());
    }

    private ConnectorConfig config(String type) {
        InterAddress address = InterAddress.forBinding(type, "127.0.0.1", 0, "default");
        Map<String, String> options = new HashMap<>();
        ProtocolConfig protocol = ProtocolConfig.newConfig(type, type, options);
        return new ConnectorConfig(type, address, options, Collections.singletonList(protocol), true);
    }

    private <M> NetworkConnector<M> createConnector(TransportFactory<M> factory, ConnectorConfig config, //
            TransportRuntimeServices runtimeServices) {
        TransportConfig transportConfig = transport(config);
        return factory.prepare(transportConfig, runtimeServices.classLoader()).create(runtimeServices);
    }

    private RouteReceiver<byte[]> blockingReceiver(//
            CountDownLatch attaching, CountDownLatch resume, AtomicBoolean connected, //
            CountDownLatch delivered, BlockingQueue<byte[]> received) {
        // 在接收器装配阶段暂停，固定 factory 已调用但 connected 尚未交付的窗口。
        attaching.countDown();
        try {
            if (!resume.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Receiver initialization was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }

        return new RouteReceiver<>() {
            @Override
            public void connected() {
                connected.set(true);
                delivered.countDown();
            }

            @Override
            public void receive(String route, byte[] message) {
                received.add(message);
            }

            @Override
            public void receive(byte[] message) {
                throw new AssertionError("Routed receiver must receive the selected route");
            }

            @Override
            public void closed(Throwable cause) {
            }
        };
    }

    private <M> RouteTable<M> routePlan(ConnectorConfig config) {
        Map<String, RouteDefinition<M>> routes = new LinkedHashMap<>();
        for (ProtocolConfig protocol : config.protocols()) {
            RouteDefinition<M> route = RouteTestAccess.definition(//
                    protocol.options(), protocol.mountIndex(), message -> RouteProbeResult.MATCH);
            routes.put(protocol.mountId(), route);
        }
        return RouteTestAccess.activate(routes, null);
    }

    private String mountId(ConnectorConfig config) {
        return config.protocols().iterator().next().mountId();
    }
}
