/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplex;
import org.junit.Test;
import static java.net.http.HttpRequest.newBuilder;
import static org.junit.Assert.*;

public class Http2TransportTest {
    @Test(timeout = 15000)
    public void oneConnectionCarriesOverlappingExchangesAndIndependentCancellation() throws Exception {
        ConnectorConfig config = config(Map.of("httpVersion", "2"));
        ClassLoader loader = this.getClass().getClassLoader();
        try (HttpConnector server = new HttpConnector(config, loader); HttpConnector client = new HttpConnector(config, loader)) {
            // 只建立一次物理连接，服务端保留每个交换对象，显式倒序响应。
            LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
            BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
            NetworkListen listen = server.bind(config.address(), channel -> receiver(channel, accepted, received));
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), channel -> receiver(channel, connected, new LinkedBlockingQueue<>()));
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            List<HttpExchange> calls = new ArrayList<>();
            List<HttpExchange> arrivals = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                HttpRequest request = HttpRequest.newBuffered("POST", "/" + i, Map.of(), new byte[] { (byte) i }, 2);
                HttpExchange exchange = new HttpExchange(request);
                calls.add(exchange);
                channel.write(exchange).get(3, TimeUnit.SECONDS);
                HttpExchange arrival = received.poll(3, TimeUnit.SECONDS);
                assertNotNull(arrival);
                assertEquals(2, arrival.request().version());
                arrivals.add(arrival);
            }
            for (HttpExchange exchange : calls) {
                assertFalse(exchange.response().isDone());
            }

            // 取消其中一条 stream，不关闭共享连接，其他交换按自己的 ID 接收响应。
            calls.get(0).complete(new IllegalStateException("cancel only this stream"));
            NetworkChannel<HttpExchange> incoming = accepted.get(3, TimeUnit.SECONDS);
            for (int i = 19; i >= 1; i--) {
                HttpExchange exchange = arrivals.get(i);
                HttpResponse response = HttpResponse.newBuffered(200, Map.of(), exchange.request().body(), Map.of());
                exchange.respond(response);
                incoming.write(exchange).get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] { (byte) i }, calls.get(i).response().get(3, TimeUnit.SECONDS).body());
            }
            assertTrue(channel.isOpen());
            assertTrue(incoming.isOpen());
        }
    }

    @Test(timeout = 15000)
    public void bodyArrivesBeforeEofAndLargerThanTheFlowWindow() throws Exception {
        ConnectorConfig config = config(Map.of("httpVersion", "2", "streamBody", "true"));
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try (HttpConnector server = new HttpConnector(config, loader); HttpConnector client = new HttpConnector(config, loader)) {
            NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<>() {
                public void connected() {
                }

                public void receive(HttpExchange exchange) {
                    // 收到头部后立即回传同一个增量正文，此时发送方还没有关闭输出。
                    HttpBody input = exchange.request().stream();
                    assertNotNull(input);
                    exchange.respond(HttpResponse.newStreaming(200, Map.of(), input, Map.of()));
                    channel.write(exchange);
                }

                public void closed(Throwable cause) {
                }
            });
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), channel -> receiver(channel, connected, new LinkedBlockingQueue<>()));
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBody body = new HttpBody(16384);
            HttpExchange exchange = new HttpExchange(HttpRequest.newStreaming("POST", "/stream", Map.of(), body, 2));
            var writing = channel.write(exchange);
            OutputStream output = body.output();
            output.write("head".getBytes(StandardCharsets.UTF_8));
            HttpBody input = exchange.response().get(3, TimeUnit.SECONDS).stream();
            assertArrayEquals("head".getBytes(StandardCharsets.UTF_8), input.readNBytes(4));
            assertFalse(writing.isDone());

            // 数据量跨过 HTTP/2 窗口；生产和消费交叠，没有聚合为一个完整报文。
            byte[] payload = new byte[280000];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
            var produced = producer.submit(() -> {
                try (output) {
                    output.write(payload);
                }
                return null;
            });
            assertArrayEquals(payload, input.readAllBytes());
            produced.get(3, TimeUnit.SECONDS);
            writing.get(3, TimeUnit.SECONDS);
            exchange.completion().get(3, TimeUnit.SECONDS);
            assertTrue(channel.isOpen());
        } finally {
            producer.shutdownNow();
        }
    }

    @Test(timeout = 15000)
    public void jdkClientUpgradesToH2cAndKeepsTheFirstRequest() throws Exception {
        ConnectorConfig config = config(Map.of());
        try (HttpConnector server = new HttpConnector(config, this.getClass().getClassLoader())) {
            NetworkListen listen = server.bind(config.address(), Http2TransportTest::echo);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            URI uri = URI.create("http://127.0.0.1:" + listen.getAddress().getPort() + "/upgrade");
            var request = newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build();
            var response = client.send(request, BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("2:/upgrade", response.body());
            assertEquals("2:/upgrade", client.send(request, BodyHandlers.ofString()).body());
        }
    }

    @Test(timeout = 15000)
    public void tlsAlpnSelectsH2OrHttp1OnTheSameListener() throws Exception {
        ConnectorConfig config = config(Map.of("transportPipeline", TlsPipeline.class.getName()));
        try (HttpConnector server = new HttpConnector(config, this.getClass().getClassLoader())) {
            NetworkListen listen = server.bind(config.address(), Http2TransportTest::echo);
            URI uri = URI.create("https://localhost:" + listen.getAddress().getPort() + "/tls");
            for (HttpClient.Version version : HttpClient.Version.values()) {
                HttpClient client = HttpClient.newBuilder().sslContext(sslContext()).version(version).build();
                var request = newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build();
                var response = client.send(request, BodyHandlers.ofString());
                assertEquals(version, response.version());
                String prefix = version == HttpClient.Version.HTTP_2 ? "2" : "1";
                assertEquals(prefix + ":/tls", response.body());
            }
        }
    }

    @Test(timeout = 15000)
    public void meteorClientUsesTlsAlpnAndRejectsAnHttp1NegotiationForH2() throws Exception {
        ClassLoader loader = this.getClass().getClassLoader();
        ConnectorConfig clientConfig = config(Map.of("transportPipeline", TlsPipeline.class.getName(), "httpVersion", "2", "httpScheme", "https"));
        for (String alpn : List.of("h2", "http/1.1")) {
            Map<String, String> options = Map.of("transportPipeline", TlsPipeline.class.getName(), "tlsAlpn", alpn);
            ConnectorConfig serverConfig = config(options);
            try (HttpConnector server = new HttpConnector(serverConfig, loader); HttpConnector client = new HttpConnector(clientConfig, loader)) {
                NetworkListen listen = server.bind(serverConfig.address(), Http2TransportTest::echo);
                BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
                LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
                client.connect(listen.getAddress(), channel -> receiver(channel, connected, received));
                HttpRequest request = HttpRequest.newBuffered("GET", "/tls", Map.of(), new byte[0], 2);
                HttpExchange exchange = new HttpExchange(request);
                connected.get(3, TimeUnit.SECONDS).write(exchange);
                if (StringUtils.equals("h2", alpn)) {
                    HttpResponse response = exchange.response().get(4, TimeUnit.SECONDS);
                    assertArrayEquals("2:/tls".getBytes(StandardCharsets.UTF_8), response.body());
                } else {
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> exchange.response().get(4, TimeUnit.SECONDS));
                    assertTrue(failure.getCause().getMessage().contains("ALPN h2"));
                }
            }
        }
    }

    private static ChannelListener<HttpExchange> echo(NetworkChannel<HttpExchange> channel) {
        return new ChannelListener<>() {
            public void connected() {
            }

            public void receive(HttpExchange exchange) {
                String text = exchange.request().version() + ":" + exchange.request().uri();
                exchange.respond(HttpResponse.newBuffered(200, Map.of(), text.getBytes(), Map.of()));
                channel.write(exchange);
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static ChannelListener<HttpExchange> receiver(NetworkChannel<HttpExchange> channel, BasicFuture<NetworkChannel<HttpExchange>> connected, LinkedBlockingQueue<HttpExchange> received) {
        return new ChannelListener<>() {
            public void connected() {
                connected.completed(channel);
            }

            public void receive(HttpExchange exchange) {
                received.add(exchange);
            }

            public void closed(Throwable cause) {
                connected.failed(cause);
            }
        };
    }

    private static ConnectorConfig config(Map<String, String> options) {
        var values = new HashMap<>(options);
        values.put("listenType", "http");
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = new ProtocolConfig("http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, values, List.of(protocol), true);
    }

    private static SSLContext sslContext() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Http2TransportTest.class.getResourceAsStream("/tls/localhost.p12")) {
            store.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    public static final class TlsPipeline implements TransportPipeline {
        public void initialize(ConnectorConfig config, ProtoBuildContext stack) throws Exception {
            SslConfig ssl = new SslConfig();
            ssl.setSslContext(sslContext());
            ssl.setAppProtocol(config.option("tlsAlpn", "h2,http/1.1").split(","));
            if (stack.getChannel().isClient()) {
                ssl.setPeerHost("localhost");
                ssl.setEndpointAlgorithm("HTTPS");
            }
            stack.addLast("tls", new SslDuplex(ssl));
        }
    }
}
