/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.http.HttpConnector;
import net.hasor.rsf.connector.transport.http.HttpExchange;
import net.hasor.rsf.connector.transport.http.HttpRequest;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.connector.transport.tcp.TcpConnector;
import net.hasor.rsf.connector.transport.udp.UdpConnector;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end use of the network API without an application protocol, manager or Payload.
 */
public class NetworkConnectorTest {

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
        try (NetworkConnector<byte[]> server = udp ? new UdpConnector(config, loader) : new TcpConnector(config, loader); NetworkConnector<byte[]> client = udp ? new UdpConnector(config, loader) : new TcpConnector(config, loader)) {
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
        try (HttpConnector server = new HttpConnector(config, loader); HttpConnector client = new HttpConnector(config, loader)) {
            BlockingQueue<HttpRequest> requests = new LinkedBlockingQueue<>();
            NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<HttpExchange>() {

                public void connected() {
                }

                public void receive(HttpExchange exchange) {
                    requests.add(exchange.request());
                    exchange.respond(new HttpResponse(201, Collections.singletonMap("X-Reply", "received"), exchange.request().body()));
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
            HttpExchange first = new HttpExchange(new HttpRequest("POST", "/plain/first", Collections.singletonMap("X-Test", "one"), "hello".getBytes(StandardCharsets.UTF_8)));
            HttpExchange second = new HttpExchange(new HttpRequest("POST", "/plain/second", Collections.emptyMap(), new byte[] { 8, 9 }));
            channel.write(first).get(2, TimeUnit.SECONDS);
            channel.write(second).get(2, TimeUnit.SECONDS);
            assertEquals(201, first.response().get(2, TimeUnit.SECONDS).status());
            assertEquals("received", first.response().get().headers().get("x-reply"));
            assertArrayEquals(first.request().body(), first.response().get().body());
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
            assertTrue(connected.get().isOpen());
        } finally {
            client.close();
        }
        closed.get(2, TimeUnit.SECONDS);
        assertFalse(connected.get().isOpen());
    }

    private ConnectorConfig config(String type) throws Exception {
        int port;
        if ("udp".equals(type)) {
            try (DatagramSocket socket = new DatagramSocket()) {
                port = socket.getLocalPort();
            }
        } else {
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
        }
        return new ConnectorConfig(type, new InterAddress(type, "127.0.0.1", port, "default"), Collections.singletonMap("listenType", type));
    }
}
