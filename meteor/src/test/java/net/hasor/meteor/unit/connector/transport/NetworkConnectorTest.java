/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelFactory;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.http.HttpConnector;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.connector.transport.tcp.TcpConnectorFactory;
import net.hasor.meteor.connector.transport.udp.UdpConnectorFactory;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 通过真实网络验证独立传输 SPI；不包含应用协议或 RPC 场景。
 */
public class NetworkConnectorTest {
    @Test
    public void transportSpiIsAvailableWithoutProtocolModule() throws Exception {
        // 发现内核提供的全部传输 SPI，并校验名称不会冲突。
        Map<String, NetworkConnectorFactory<?>> factories = new HashMap<>();
        ClassLoader loader = this.getClass().getClassLoader();
        for (NetworkConnectorFactory<?> factory : ServiceLoader.load(NetworkConnectorFactory.class, loader)) {
            assertNull(factories.put(factory.name(), factory));
        }
        // 每种传输的消息类型和创建契约不依赖协议插件。
        assertEquals(Set.of("tcp", "udp", "http"), factories.keySet());
        assertEquals(byte[].class, factories.get("tcp").messageType());
        assertEquals(byte[].class, factories.get("udp").messageType());
        assertEquals(HttpExchange.class, factories.get("http").messageType());
        for (NetworkConnectorFactory<?> factory : factories.values()) {
            try (NetworkConnector<?> network = factory.create(this.config(factory.name()), loader)) {
                assertNotNull(network);
            }
        }
    }

    @Test(timeout = 10000)
    public void tcpIsUsableWithoutRsf() throws Exception {
        // 仅配置 TCP 传输，直接验证 SPI 的监听器退出契约。
        ConnectorConfig config = this.config("tcp");
        ClassLoader loader = this.getClass().getClassLoader();
        NetworkConnectorFactory<byte[]> factory = new TcpConnectorFactory();
        try (NetworkConnector<byte[]> server = factory.create(config, loader); //
             NetworkConnector<byte[]> client = factory.create(config, loader)) {
            // 监听和连接均使用真实套接字，接收端原样回送字节。
            BasicFuture<NetworkChannel<byte[]>> connected = new BasicFuture<>();
            BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
            NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<byte[]>() {

                public void connected() {
                }

                public void receive(byte[] bytes) {
                    channel.write(bytes);
                }

                public void closed(Throwable cause) {
                }
            });
            client.connect(listen.getAddress(), channel -> new ChannelListener<byte[]>() {

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
    public void udpIsUsableWithoutRsf() throws Exception {
        // 通过 JDK 临时套接字选择 UDP 端口，再向传输 SPI 显式传入非零监听端口。
        int port;
        try (DatagramSocket available = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            port = available.getLocalPort();
        }
        InterAddress address = new InterAddress("udp", "127.0.0.1", port, "default");
        Map<String, String> options = Map.of("listenType", "udp");
        ProtocolConfig protocol = new ProtocolConfig("udp", "udp", "udp", options);
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        ClassLoader loader = this.getClass().getClassLoader();
        NetworkConnectorFactory<byte[]> factory = new UdpConnectorFactory();
        try (NetworkConnector<byte[]> server = factory.create(config, loader); //
             NetworkConnector<byte[]> client = factory.create(config, loader)) {
            // 监听和连接均使用真实套接字，接收端原样回送字节。
            BasicFuture<NetworkChannel<byte[]>> connected = new BasicFuture<>();
            BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
            NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<byte[]>() {

                public void connected() {
                }

                public void receive(byte[] bytes) {
                    channel.write(bytes);
                }

                public void closed(Throwable cause) {
                }
            });
            client.connect(listen.getAddress(), channel -> new ChannelListener<byte[]>() {

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
    public void udpRejectsZeroBindPortWithoutPreventingValidBind() throws Exception {
        // 构造配置使用合法端口，单独向 bind 传入零端口地址。
        int port;
        try (DatagramSocket available = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            port = available.getLocalPort();
        }
        InterAddress address = new InterAddress("udp", "127.0.0.1", port, "default");
        Map<String, String> options = Map.of("listenType", "udp");
        ProtocolConfig protocol = new ProtocolConfig("udp", "udp", "udp", options);
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<byte[]> server = new UdpConnectorFactory().create(config, loader)) {
            AtomicBoolean peerCreated = new AtomicBoolean();
            ChannelFactory<byte[]> receiver = channel -> {
                peerCreated.set(true);
                throw new AssertionError("Binding alone must not create a peer channel");
            };

            // 校验实际绑定参数，直接拒绝零端口且不创建对端通道。
            InterAddress zeroPort = InterAddress.forBinding("udp", "127.0.0.1", 0, "default");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
                server.bind(zeroPort, receiver);
            });
            assertEquals("UDP listen port must be between 1 and 65535", error.getMessage());
            assertFalse(peerCreated.get());

            // 参数错误不会破坏连接器，随后可按合法地址正常监听和关闭。
            NetworkListen listen = server.bind(address, receiver);
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
        try (HttpConnector server = new HttpConnector(config, loader); //
             HttpConnector client = new HttpConnector(config, loader)) {
            BlockingQueue<HttpRequest> requests = new LinkedBlockingQueue<>();
            NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<HttpExchange>() {

                public void connected() {
                }

                public void receive(HttpExchange exchange) {
                    requests.add(exchange.request());
                    Map<String, List<String>> headers = Map.of("X-Reply", List.of("received"));
                    HttpResponse response = HttpResponse.newBuffered(201, headers, exchange.request().body(), Map.of());
                    exchange.respond(response);
                    channel.write(exchange);
                }

                public void closed(Throwable cause) {
                }
            });
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), channel -> new ChannelListener<HttpExchange>() {

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
        HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader());
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try {
            InterAddress unusedRemote = new InterAddress("http", "127.0.0.1", 1, "default");
            client.connect(unusedRemote, channel -> new ChannelListener<HttpExchange>() {

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
        Map<String, String> options = Collections.singletonMap("listenType", type);
        ProtocolConfig protocol = new ProtocolConfig(type, type, type, options);
        return new ConnectorConfig(type, address, options, Collections.singletonList(protocol), true);
    }
}
