/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import org.junit.Test;
import static java.net.http.HttpRequest.newBuilder;
import static net.hasor.meteor.connector.HttpTransportPlans.routes;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.TransportTestServices.newHttpConnector;
import static org.junit.Assert.*;

/** 在真实明文连接上验证 HTTP/2 传输 SPI。 */
public class Http2TransportTest {
    @Test(timeout = 15000)
    public void acceptedSocketPublishesOnlySelectedHttp2Branch() throws Exception {
        // 同一入站 socket 会预初始化 HTTP/1 和 HTTP/2 分支，但只能发布实际选中的 HTTP/2 通道。
        ConnectorConfig config = config(Map.of("httpVersion", "2"));
        ClassLoader loader = this.getClass().getClassLoader();
        AtomicInteger published = new AtomicInteger();
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ChannelListenerFactory<HttpExchange> delegate = receiver(accepted, received);
        ChannelListenerFactory<HttpExchange> observing = channel -> {
            published.incrementAndGet();
            return delegate.createListener(channel);
        };

        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), observing)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(config),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);

            // 首个请求完成服务端分支选择，未选中的 HTTP/1 wrapper 不能触发 ChannelListenerFactory。
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpRequest request = HttpRequest.newBuffered("GET", "/selected", Map.of(), new byte[0], 2, Map.of());
            HttpExchange outgoing = new HttpExchange(request);
            channel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertEquals(2, incoming.request().version());
            assertEquals(1, published.get());
        }
    }

    @Test(timeout = 15000)
    public void h2cPriorKnowledgeMatchesOverlappingExchangesToReversedResponses() throws Exception {
        // 只建立一条物理连接，服务端暂存二十个并行未完成的交换。
        ConnectorConfig config = config(Map.of("httpVersion", "2"));
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(accepted, received))) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(config),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
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
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(accepted, received))) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(config),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
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
    public void drainingServerRefusesNewStreamsWhileFinishingASubmittedResponse() throws Exception {
        // 服务端保留一个尚未结束的流式响应，客户端按流读取响应正文。
        ConnectorConfig incoming = config(Map.of("httpVersion", "2"));
        ConnectorConfig outgoing = config(Map.of("httpVersion", "2", "streamBody", "true"));
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(incoming), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(outgoing), loader); //
             NetworkListen listen = server.bind(incoming.address(), routes(incoming), receiver(accepted, received))) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(outgoing),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = connected.get(3, TimeUnit.SECONDS);
            HttpRequest firstRequest = HttpRequest.newBuffered("GET", "/first", Map.of(), //
                    new byte[0], 2, Map.of());
            HttpExchange first = new HttpExchange(firstRequest);
            outgoingChannel.write(first).get(3, TimeUnit.SECONDS);
            HttpExchange firstArrival = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(firstArrival);
            NetworkChannel<HttpExchange> incomingChannel = accepted.get(3, TimeUnit.SECONDS);
            HttpBodyStream responseBody = new HttpBodyStream(1024);
            firstArrival.respond(HttpResponse.newStreaming(200, Map.of(), responseBody, Map.of()));
            var writing = incomingChannel.write(firstArrival);
            HttpResponse firstResponse = first.response().get(3, TimeUnit.SECONDS);
            assertNotNull(firstResponse.stream());

            // prepareDrain 排在标记任务之前执行；此后新 stream 必须被明确拒绝。
            var draining = incomingChannel.drainAndClose();
            CountDownLatch prepared = new CountDownLatch(1);
            incomingChannel.execute(prepared::countDown);
            assertTrue(prepared.await(3, TimeUnit.SECONDS));
            assertFalse(draining.isDone());
            HttpRequest secondRequest = HttpRequest.newBuffered("GET", "/second", Map.of(), //
                    new byte[0], 2, Map.of());
            HttpExchange second = new HttpExchange(secondRequest);
            outgoingChannel.write(second).get(3, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class, () -> {
                second.completion().get(3, TimeUnit.SECONDS);
            });
            assertTrue(refused.getCause() instanceof IOException);
            assertEquals("HTTP/2 stream reset: 7", refused.getCause().getMessage());
            assertTrue(received.isEmpty());

            // 排空前已经提交的首个响应继续写完，随后物理连接才关闭。
            responseBody.finish(Map.of());
            assertEquals(-1, firstResponse.stream().read());
            writing.get(3, TimeUnit.SECONDS);
            first.completion().get(3, TimeUnit.SECONDS);
            draining.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void responseBodyArrivesBeforeRequestEof() throws Exception {
        // 开启增量正文，服务端在收到头部后立即回传同一个正文流。
        ConnectorConfig config = config(Map.of("httpVersion", "2", "streamBody", "true"));
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), //
                     Http2TransportTest::streamingEcho)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(config),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBodyStream body = new HttpBodyStream(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/stream", Map.of(), body, 2);
            HttpExchange exchange = new HttpExchange(request);

            // 请求输出仍未 EOF，客户端已经可以消费第一个响应块。
            var writing = channel.write(exchange);
            byte[] head = "head".getBytes(StandardCharsets.UTF_8);
            body.output().write(head);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream input = response.stream();
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
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), //
                     Http2TransportTest::streamingEcho)) {
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(null, listen.getAddress(), routes(config),  //
                            receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBodyStream body = new HttpBodyStream(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/stream", Map.of(), body, 2);
            HttpExchange exchange = new HttpExchange(request);
            var writing = channel.write(exchange);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream input = response.stream();
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
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), Http2TransportTest::echo)) {
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
    public void interleavedBodyModesKeepRoutesIsolatedWithinEachStream() throws Exception {
        // 两个普通挂载使用不同正文模式，同时配置 WebSocket 以覆盖嵌套分支上下文。
        ConnectorConfig config = routingConfig();
        ClassLoader loader = this.getClass().getClassLoader();
        RouteTable<HttpExchange> compiled = routes(config);
        LinkedBlockingQueue<String> selected = new LinkedBlockingQueue<>();
        RouteTable<HttpExchange> incomingRoutes = RouteTestAccess.activate(compiled.definitions(), uri -> {
            selected.add(uri);
            return compiled.select(uri);
        });
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), incomingRoutes, receiver(accepted, received))) {
            client.connect(null, listen.getAddress(), compiled, //
                    receiver(connected, new LinkedBlockingQueue<>())).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // 第一个 stream 已选择聚合路由，但正文尚未结束，暂时不能交付应用。
            HttpBodyStream bufferedUpload = new HttpBodyStream(16);
            bufferedUpload.output().write(1);
            HttpRequest buffered = HttpRequest.newStreaming("POST", "/buffered", Map.of(), bufferedUpload, 2);
            var bufferedWriting = channel.write(new HttpExchange(buffered));
            assertEquals("/buffered", selected.poll(3, TimeUnit.SECONDS));

            // 另一个 stream 先交付流式正文，其路由不能覆盖前一个 stream 的选择。
            HttpBodyStream streamingUpload = new HttpBodyStream(16);
            streamingUpload.output().write(2);
            HttpRequest streaming = HttpRequest.newStreaming("POST", "/streaming", Map.of(), streamingUpload, 2);
            var streamingWriting = channel.write(new HttpExchange(streaming));
            assertEquals("/streaming", selected.poll(3, TimeUnit.SECONDS));
            HttpExchange streamedArrival = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(streamedArrival);
            assertEquals("/streaming", streamedArrival.request().uri());
            RouteSelection streamedRoute = streamedArrival.request().route();
            assertNotNull(streamedRoute);
            assertEquals("streaming", streamedRoute.protocolName());
            HttpBodyStream streamedBody = streamedArrival.request().stream();
            assertNotNull(streamedBody);

            // 后完成的聚合请求仍携带自己的路由，两个正文也保持独立。
            bufferedUpload.finish(Map.of());
            bufferedWriting.get(3, TimeUnit.SECONDS);
            HttpExchange bufferedArrival = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(bufferedArrival);
            assertEquals("/buffered", bufferedArrival.request().uri());
            RouteSelection bufferedRoute = bufferedArrival.request().route();
            assertNotNull(bufferedRoute);
            assertEquals("buffered", bufferedRoute.protocolName());
            assertNull(bufferedArrival.request().stream());
            assertArrayEquals(new byte[] { 1 }, bufferedArrival.request().body());
            streamingUpload.finish(Map.of());
            streamingWriting.get(3, TimeUnit.SECONDS);
            byte[] streamedBytes = reader.submit(streamedBody::readAllBytes).get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 2 }, streamedBytes);
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void bufferedRequestKeepsHeaderRouteAfterMappingChanges() throws Exception {
        // 动态路由初始指向聚合挂载，使用可观察的路由查询确认头部已经完成选择。
        ConnectorConfig config = routingConfig();
        ClassLoader loader = this.getClass().getClassLoader();
        RouteTable<HttpExchange> compiled = routes(config);
        RouteSelection original = compiled.select("/buffered");
        AtomicReference<RouteSelection> current = new AtomicReference<>(original);
        BasicFuture<RouteSelection> selected = new BasicFuture<>();
        RouteTable<HttpExchange> incomingRoutes = RouteTestAccess.activate(compiled.definitions(), uri -> {
            RouteSelection route = current.get();
            selected.completed(route);
            return route;
        });
        BasicFuture<NetworkChannel<HttpExchange>> accepted = new BasicFuture<>();
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), incomingRoutes, receiver(accepted, received))) {
            client.connect(null, listen.getAddress(), compiled, //
                    receiver(connected, new LinkedBlockingQueue<>())).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpBodyStream upload = new HttpBodyStream(16);
            upload.output().write(3);
            HttpRequest request = HttpRequest.newStreaming("POST", "/buffered", Map.of(), upload, 2);
            var writing = channel.write(new HttpExchange(request));
            assertSame(original, selected.get(3, TimeUnit.SECONDS));

            // 正文完成前变更动态映射，当前请求继续使用头部阶段保存在 stream 上的结果。
            current.set(compiled.select("/streaming"));
            upload.finish(Map.of());
            writing.get(3, TimeUnit.SECONDS);
            HttpExchange incoming = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertSame(original, incoming.request().route());
            assertNull(incoming.request().stream());
            assertArrayEquals(new byte[] { 3 }, incoming.request().body());
        }
    }

    private static ConnectorConfig routingConfig() {
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        Map<String, String> options = Map.of("httpVersion", "2");
        Map<String, String> buffered = Map.of("contextPath", "/buffered", "streamBody", "false");
        Map<String, String> streaming = Map.of("contextPath", "/streaming", "streamBody", "true");
        Map<String, String> websocket = Map.of("contextPath", "/ws", "upgrade", "websocket");
        List<ProtocolConfig> protocols = new ArrayList<>();
        protocols.add(ProtocolConfig.newConfig("buffered", "http", buffered));
        protocols.add(ProtocolConfig.newConfig("streaming", "http", streaming));
        protocols.add(ProtocolConfig.newConfig("websocket", "http", websocket));
        return new ConnectorConfig("http", address, options, protocols, true);
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
                HttpBodyStream input = exchange.request().stream();
                assertNotNull(input);
                exchange.respond(HttpResponse.newStreaming(200, Map.of(), input, Map.of()));
                channel.write(exchange);
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static ChannelListenerFactory<HttpExchange> receiver(BasicFuture<NetworkChannel<HttpExchange>> connected, //
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
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, values, List.of(protocol), true);
    }

}
