/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpConnector;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.connector.transport.tcp.TcpConnectorFactory;
import net.hasor.meteor.connector.transport.udp.UdpConnectorFactory;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end use of the network API without an application protocol, manager or Payload.
 */
public class NetworkConnectorTest {
    @Test
    public void transportSpiIsAvailableWithoutProtocolModule() throws Exception {
        Map<String, NetworkConnectorFactory<?>> factories = new HashMap<>();
        ClassLoader loader = this.getClass().getClassLoader();
        for (NetworkConnectorFactory<?> factory : ServiceLoader.load(NetworkConnectorFactory.class, loader)) {
            assertNull(factories.put(factory.name(), factory));
        }
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
        this.byteRoundTrip(false);
    }

    @Test(timeout = 10000)
    public void udpIsUsableWithoutRsf() throws Exception {
        this.byteRoundTrip(true);
    }

    private void byteRoundTrip(boolean udp) throws Exception {
        ConnectorConfig config = this.config(udp ? "udp" : "tcp");
        ClassLoader loader = this.getClass().getClassLoader();
        NetworkConnectorFactory<byte[]> factory;
        if (udp) {
            factory = new UdpConnectorFactory();
        } else {
            factory = new TcpConnectorFactory();
        }
        try (NetworkConnector<byte[]> server = factory.create(config, loader); //
             NetworkConnector<byte[]> client = factory.create(config, loader)) {
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
            channel.write(new byte[] { 1, 2, 3 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1, 2, 3 }, received.poll(2, TimeUnit.SECONDS));
            // Closing the listener must leave existing accepted connections usable.
            listen.close();
            assertFalse(listen.isOpen());
            channel.write(new byte[] { 4, 5 }).get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 4, 5 }, received.poll(2, TimeUnit.SECONDS));
            channel.drainAndClose().get(2, TimeUnit.SECONDS);
            assertFalse(channel.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void httpCarriesCompleteMessagesWithoutRsf() throws Exception {
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
                    Map<String, String> headers = Collections.singletonMap("X-Reply", "received");
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
            Map<String, String> headers = Collections.singletonMap("X-Test", "one");
            byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
            HttpRequest firstRequest = HttpRequest.newBuffered("POST", "/plain/first", headers, text, 1);
            HttpExchange first = new HttpExchange(firstRequest);
            byte[] bytes = { 8, 9 };
            HttpRequest secondRequest = HttpRequest.newBuffered("POST", "/plain/second", Collections.emptyMap(), bytes, 1);
            HttpExchange second = new HttpExchange(secondRequest);
            channel.write(first).get(2, TimeUnit.SECONDS);
            channel.write(second).get(2, TimeUnit.SECONDS);
            HttpResponse firstResponse = first.response().get(2, TimeUnit.SECONDS);
            assertEquals(201, firstResponse.status());
            assertEquals("received", firstResponse.headers().get("x-reply"));
            assertArrayEquals(first.request().body(), firstResponse.body());
            assertArrayEquals(second.request().body(), second.response().get(2, TimeUnit.SECONDS).body());
            assertEquals("one", requests.poll(2, TimeUnit.SECONDS).headers().get("x-test"));
            assertEquals("/plain/second", requests.poll(2, TimeUnit.SECONDS).uri());
            channel.drainAndClose().get(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 10000)
    public void closingHttpConnectorClosesUnusedLogicalChannel() throws Exception {
        ConnectorConfig config = this.config("http");
        HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader());
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try {
            client.connect(config.address(), channel -> new ChannelListener<HttpExchange>() {

                public void connected() {
                    connected.completed(channel);
                }

                public void receive(HttpExchange message) {
                }

                public void closed(Throwable cause) {
                    closed.completed(null);
                }
            }).get(2, TimeUnit.SECONDS);
            assertTrue(connected.get(2, TimeUnit.SECONDS).isOpen());
        } finally {
            client.close();
        }
        closed.get(2, TimeUnit.SECONDS);
        assertFalse(connected.get(2, TimeUnit.SECONDS).isOpen());
    }

    private ConnectorConfig config(String type) throws Exception {
        int port;
        if (StringUtils.equals("udp", type)) {
            try (DatagramSocket socket = new DatagramSocket()) {
                port = socket.getLocalPort();
            }
        } else {
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
        }
        InterAddress address = new InterAddress(type, "127.0.0.1", port, "default");
        Map<String, String> options = Collections.singletonMap("listenType", type);
        ProtocolConfig protocol = new ProtocolConfig(type, type, type, options);
        return new ConnectorConfig(type, address, options, Collections.singletonList(protocol), true);
    }
}
