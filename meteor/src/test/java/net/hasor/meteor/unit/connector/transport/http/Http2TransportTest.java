/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelFactory;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.meteor.connector.transport.http.HttpBody;
import net.hasor.meteor.connector.transport.http.HttpConnector;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplex;
import org.junit.Test;
import static java.net.http.HttpRequest.newBuilder;
import static org.junit.Assert.*;

/** 在真实 TCP/TLS 连接上验证 HTTP/2 传输 SPI。 */
public class Http2TransportTest {
    @Test(timeout = 15000)
    public void oneConnectionMatchesOverlappingExchangesToReversedResponses() throws Exception {
        // 只建立一条物理连接，服务端暂存二十个并行未完成的交换。
        ConnectorConfig config = config(Map.of("httpVersion", "2"));
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        try (HttpConnector server = new HttpConnector(config, loader); //
             HttpConnector client = new HttpConnector(config, loader); //
             NetworkListen listen = server.bind(config.address(), receiver(accepted, received))) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            List<HttpExchange> calls = new ArrayList<>();
            List<HttpExchange> arrivals = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String path = "/" + i;
                byte[] body = { (byte) i };
                HttpRequest request = HttpRequest.newBuffered("POST", path, Map.of(), body, 2, Map.of());
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

            // 所有响应倒序发回，每次仍匹配到原请求的 stream ID。
            NetworkChannel<HttpExchange> incoming = accepted.get(3, TimeUnit.SECONDS);
            for (int i = calls.size() - 1; i >= 0; i--) {
                HttpExchange arrival = arrivals.get(i);
                byte[] requestBody = arrival.request().body();
                arrival.respond(HttpResponse.newBuffered(200, Map.of(), requestBody, Map.of()));
                incoming.write(arrival).get(3, TimeUnit.SECONDS);
                HttpExchange call = calls.get(i);
                HttpResponse response = call.response().get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] { (byte) i }, response.body());
            }
            assertTrue(channel.isOpen());
            assertTrue(incoming.isOpen());
        }
    }

    @Test(timeout = 15000)
    public void cancellingOneStreamLeavesAnotherExchangeUsable() throws Exception {
        // 在一条物理连接上准备两个已被服务端接收的请求。
        ConnectorConfig config = config(Map.of("httpVersion", "2"));
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        try (HttpConnector server = new HttpConnector(config, loader); //
             HttpConnector client = new HttpConnector(config, loader); //
             NetworkListen listen = server.bind(config.address(), receiver(accepted, received))) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpRequest cancelRequest = HttpRequest.newBuffered("GET", "/cancel", Map.of(), new byte[0], 2, Map.of());
            HttpExchange cancelled = new HttpExchange(cancelRequest);
            channel.write(cancelled).get(3, TimeUnit.SECONDS);
            HttpExchange cancelledArrival = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(cancelledArrival);
            HttpRequest survivingRequest = HttpRequest.newBuffered("GET", "/keep", Map.of(), new byte[0], 2, Map.of());
            HttpExchange surviving = new HttpExchange(survivingRequest);
            channel.write(surviving).get(3, TimeUnit.SECONDS);
            HttpExchange survivingArrival = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(survivingArrival);

            // 只取消第一条流，调用方收到原始取消原因，对端也观察到该流失败。
            IllegalStateException cancellation = new IllegalStateException("cancel only this stream");
            cancelled.complete(cancellation);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                cancelled.response().get(3, TimeUnit.SECONDS);
            });
            assertSame(cancellation, failure.getCause());
            assertThrows(ExecutionException.class, () -> {
                cancelledArrival.completion().get(3, TimeUnit.SECONDS);
            });

            // 另一条已接收的流仍可完成，连接两端都保持打开。
            assertFalse(surviving.response().isDone());
            survivingArrival.respond(HttpResponse.newBuffered(200, Map.of(), new byte[] { 7 }, Map.of()));
            NetworkChannel<HttpExchange> incoming = accepted.get(3, TimeUnit.SECONDS);
            incoming.write(survivingArrival).get(3, TimeUnit.SECONDS);
            HttpResponse response = surviving.response().get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 7 }, response.body());
            assertTrue(channel.isOpen());
            assertTrue(incoming.isOpen());
        }
    }

    @Test(timeout = 15000)
    public void responseBodyArrivesBeforeRequestEof() throws Exception {
        // 开启增量正文，服务端在收到头部后立即回传同一个正文流。
        ConnectorConfig config = config(Map.of("httpVersion", "2", "streamBody", "true"));
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try (HttpConnector server = new HttpConnector(config, loader); //
             HttpConnector client = new HttpConnector(config, loader); //
             NetworkListen listen = server.bind(config.address(), Http2TransportTest::streamingEcho)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBody body = new HttpBody(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/stream", Map.of(), body, 2);
            HttpExchange exchange = new HttpExchange(request);

            // 请求输出仍未 EOF，客户端已经可以消费第一个响应块。
            var writing = channel.write(exchange);
            byte[] head = "head".getBytes(StandardCharsets.UTF_8);
            body.output().write(head);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBody input = response.stream();
            assertNotNull(input);
            var reading = consumer.submit(() -> input.readNBytes(head.length));
            byte[] firstChunk = reading.get(3, TimeUnit.SECONDS);
            assertArrayEquals(head, firstChunk);
            assertFalse(writing.isDone());

            // 显式结束请求方向，响应随之到达 EOF 并完成交换。
            body.finish(Map.of());
            var eof = consumer.submit(() -> input.read());
            assertEquals(Integer.valueOf(-1), eof.get(3, TimeUnit.SECONDS));
            writing.get(3, TimeUnit.SECONDS);
            exchange.completion().get(3, TimeUnit.SECONDS);
            assertTrue(channel.isOpen());
        } finally {
            consumer.shutdownNow();
            assertTrue(consumer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void unconsumedResponseBackpressuresAnUploadLargerThanTheFlowWindow() throws Exception {
        // 正文大于 HTTP/2 窗口和本地缓存，使用独立生产与消费线程观察背压。
        ConnectorConfig config = config(Map.of("httpVersion", "2", "streamBody", "true"));
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService io = Executors.newFixedThreadPool(2);
        byte[] payload = new byte[2 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        try (HttpConnector server = new HttpConnector(config, loader); //
             HttpConnector client = new HttpConnector(config, loader); //
             NetworkListen listen = server.bind(config.address(), Http2TransportTest::streamingEcho)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBody body = new HttpBody(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/stream", Map.of(), body, 2);
            HttpExchange exchange = new HttpExchange(request);
            var writing = channel.write(exchange);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBody input = response.stream();
            assertNotNull(input);

            // 生产已经开始但响应尚未消费，生产者不能把整个正文无限堆入编码队列。
            CountDownLatch producing = new CountDownLatch(1);
            var produced = io.submit(() -> {
                try (OutputStream output = body.output()) {
                    producing.countDown();
                    output.write(payload);
                }
                return null;
            });
            assertTrue(producing.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> produced.get(100, TimeUnit.MILLISECONDS));

            // 开始消费后窗口持续归还，完整正文按原顺序到达且连接仍可使用。
            var consumed = io.submit(() -> input.readAllBytes());
            byte[] received = consumed.get(6, TimeUnit.SECONDS);
            assertArrayEquals(payload, received);
            produced.get(3, TimeUnit.SECONDS);
            writing.get(3, TimeUnit.SECONDS);
            exchange.completion().get(3, TimeUnit.SECONDS);
            assertTrue(channel.isOpen());
        } finally {
            io.shutdownNow();
            assertTrue(io.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void jdkClientUpgradesToH2cAndKeepsTheFirstRequest() throws Exception {
        // 监听明文 HTTP，由 JDK 客户端发起 HTTP/1.1 到 h2c 的升级。
        ConnectorConfig config = config(Map.of());
        try (HttpConnector server = new HttpConnector(config, this.getClass().getClassLoader()); //
             NetworkListen listen = server.bind(config.address(), Http2TransportTest::echo)) {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            URI uri = URI.create("http://127.0.0.1:" + listen.getAddress().getPort() + "/upgrade");
            var request = newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build();

            // 升级请求本身必须被保留，升级后同一客户端的后续请求也能正常完成。
            var first = client.send(request, BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, first.version());
            assertEquals("2:/upgrade", first.body());
            var next = client.send(request, BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, next.version());
            assertEquals("2:/upgrade", next.body());
        }
    }

    @Test(timeout = 15000)
    public void tlsAlpnSelectsH2AndHttp1OnTheSameListener() throws Exception {
        // 同一个 TLS 监听器同时声明 h2 和 http/1.1，分别接受两种客户端选择。
        ConnectorConfig config = config(Map.of("transportPipeline", TlsPipeline.class.getName()));
        try (HttpConnector server = new HttpConnector(config, this.getClass().getClassLoader()); //
             NetworkListen listen = server.bind(config.address(), Http2TransportTest::echo)) {
            URI uri = URI.create("https://localhost:" + listen.getAddress().getPort() + "/tls");
            var request = newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build();
            SSLContext tls = sslContext();
            HttpClient h2 = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_2).build();
            HttpClient http1 = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build();

            // 两次请求使用相同监听地址，响应版本和服务端识别到的版本必须一致。
            var h2Response = h2.send(request, BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, h2Response.version());
            assertEquals("2:/tls", h2Response.body());
            var http1Response = http1.send(request, BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_1_1, http1Response.version());
            assertEquals("1:/tls", http1Response.body());
        }
    }

    @Test(timeout = 15000)
    public void meteorClientUsesTlsAlpnToSendAnH2Request() throws Exception {
        // 客户端要求 HTTPS 与 HTTP/2，服务端仅声明 h2。
        String pipeline = TlsPipeline.class.getName();
        Map<String, String> clientOptions = new HashMap<>(Map.of("httpVersion", "2", "httpScheme", "https"));
        clientOptions.put("transportPipeline", pipeline);
        ConnectorConfig clientConfig = config(clientOptions);
        ConnectorConfig serverConfig = config(Map.of("transportPipeline", pipeline, "tlsAlpn", "h2"));
        ClassLoader loader = this.getClass().getClassLoader();
        try (HttpConnector server = new HttpConnector(serverConfig, loader); //
             HttpConnector client = new HttpConnector(clientConfig, loader); //
             NetworkListen listen = server.bind(serverConfig.address(), Http2TransportTest::echo)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // ALPN 选择 h2 后，请求以 HTTP/2 到达服务端并返回对应版本。
            HttpRequest request = HttpRequest.newBuffered("GET", "/tls", Map.of(), new byte[0], 2, Map.of());
            HttpExchange exchange = new HttpExchange(request);
            channel.write(exchange).get(3, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().get(4, TimeUnit.SECONDS);
            assertArrayEquals("2:/tls".getBytes(StandardCharsets.UTF_8), response.body());
        }
    }

    @Test(timeout = 15000)
    public void meteorH2ClientRejectsHttp1TlsNegotiation() throws Exception {
        // 客户端要求 HTTP/2，但服务端 TLS 仅允许 http/1.1。
        String pipeline = TlsPipeline.class.getName();
        Map<String, String> clientOptions = new HashMap<>(Map.of("httpVersion", "2", "httpScheme", "https"));
        clientOptions.put("transportPipeline", pipeline);
        ConnectorConfig clientConfig = config(clientOptions);
        ConnectorConfig serverConfig = config(Map.of("transportPipeline", pipeline, "tlsAlpn", "http/1.1"));
        ClassLoader loader = this.getClass().getClassLoader();
        try (HttpConnector server = new HttpConnector(serverConfig, loader); //
             HttpConnector client = new HttpConnector(clientConfig, loader); //
             NetworkListen listen = server.bind(serverConfig.address(), Http2TransportTest::echo)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(listen.getAddress(), receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // 协商失败必须作为调用错误暴露，不能将请求降级发送或仅靠超时结束。
            HttpRequest request = HttpRequest.newBuffered("GET", "/tls", Map.of(), new byte[0], 2, Map.of());
            HttpExchange exchange = new HttpExchange(request);
            channel.write(exchange);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                exchange.response().get(4, TimeUnit.SECONDS);
            });
            String message = failure.getCause().getMessage();
            assertTrue(message, message.contains("ALPN h2"));
        }
    }

    private static ChannelListener<HttpExchange> echo(NetworkChannel<HttpExchange> channel) {
        return new ChannelListener<>() {
            public void connected() {
            }

            public void receive(HttpExchange exchange) {
                String text = exchange.request().version() + ":" + exchange.request().uri();
                byte[] body = text.getBytes(StandardCharsets.UTF_8);
                exchange.respond(HttpResponse.newBuffered(200, Map.of(), body, Map.of()));
                channel.write(exchange);
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static ChannelListener<HttpExchange> streamingEcho(NetworkChannel<HttpExchange> channel) {
        return new ChannelListener<>() {
            public void connected() {
            }

            public void receive(HttpExchange exchange) {
                // 收到头部就回传同一个增量正文，输出何时 EOF 由测试中的生产者决定。
                HttpBody input = exchange.request().stream();
                assertNotNull(input);
                exchange.respond(HttpResponse.newStreaming(200, Map.of(), input, Map.of()));
                channel.write(exchange);
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static ChannelFactory<HttpExchange> receiver(BasicFuture<NetworkChannel<HttpExchange>> connected, //
            LinkedBlockingQueue<HttpExchange> received) {
        return channel -> new ChannelListener<>() {
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
        Map<String, String> values = new HashMap<>(options);
        values.put("listenType", "http");
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = new ProtocolConfig("http", "http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, values, List.of(protocol), true);
    }

    private static SSLContext sslContext() throws Exception {
        // 测试证书同时作为服务端身份和对端信任来源。
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Http2TransportTest.class.getResourceAsStream("/tls/localhost.p12")) {
            store.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);

        // 显式初始化 TLS 上下文，避免依赖运行环境默认信任库。
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    public static final class TlsPipeline implements TransportPipeline {
        public void initialize(ConnectorConfig config, ProtoBuildContext stack) throws Exception {
            // 从本测试配置读取允许协商的协议列表。
            SslConfig ssl = new SslConfig();
            ssl.setSslContext(sslContext());
            String protocols = config.option("tlsAlpn", "h2,http/1.1");
            ssl.setAppProtocol(protocols.split(","));

            // 客户端验证 localhost 证书，两端都安装真实 TLS 处理器。
            if (stack.getChannel().isClient()) {
                ssl.setPeerHost("localhost");
                ssl.setEndpointAlgorithm("HTTPS");
            }
            stack.addLast("tls", new SslDuplex(ssl));
        }
    }
}
