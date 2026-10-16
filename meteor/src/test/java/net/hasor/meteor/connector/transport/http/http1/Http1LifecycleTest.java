/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import org.junit.Test;
import static java.net.http.HttpRequest.newBuilder;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.TransportTestServices.newHttpConnector;
import static net.hasor.meteor.connector.HttpTransportPlans.routes;
import static org.junit.Assert.*;

/** 在真实连接上验证 HTTP/1 传输 SPI 的正文与关闭契约。 */
public class Http1LifecycleTest {
    @Test(timeout = 15000)
    public void headResponsePreservesExplicitRepresentationLength() throws Exception {
        // 启动真实 HTTP/1 监听，并发送关闭连接的 HEAD 请求以便观察响应结束位置。
        ConnectorConfig config = config(false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(connected, requests)); //
             Socket peer = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            peer.setSoTimeout(3000);
            send(peer, "HEAD /definition HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            HttpExchange exchange = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(exchange);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // HEAD 的空正文仍声明对应 GET 表示的实体长度。
            Map<String, List<String>> headers = Map.of("Content-Length", List.of("128"));
            exchange.respond(HttpResponse.newBuffered(200, headers, new byte[0], Map.of()));
            channel.write(exchange).get(3, TimeUnit.SECONDS);

            // 线级响应保留实体长度，同时连接在头部后结束，没有发送正文。
            InputStream input = peer.getInputStream();
            assertEquals("HTTP/1.1 200 OK", line(input));
            assertEquals("128", header(input, "Content-Length"));
            assertEquals(-1, input.read());
        }
    }

    @Test(timeout = 15000)
    public void pipelinedResponseCannotOvertakeAnIncrementalBody() throws Exception {
        // 配置普通请求接收，使用原始对端在同一连接发送两个流水线请求。
        ConnectorConfig config = config(false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(connected, requests)); //
             Socket peer = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            peer.setSoTimeout(3000);
            String firstRequest = "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n";
            String secondRequest = "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n";
            send(peer, firstRequest + secondRequest);
            HttpExchange first = requests.poll(3, TimeUnit.SECONDS);
            HttpExchange second = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // 第二个响应先准备好，也必须等待前一个增量响应的 EOF。
            second.respond(HttpResponse.newBuffered(200, Map.of(), new byte[] { 9 }, Map.of()));
            var secondWrite = channel.write(second);
            HttpBodyStream body = new HttpBodyStream(16);
            Map<String, List<String>> trailers = Map.of("x-final", List.of("one", "two"));
            first.respond(HttpResponse.newStreaming(200, Map.of(), body, trailers));
            var firstWrite = channel.write(first);
            body.output().write(new byte[] { 1, 2 });
            InputStream input = peer.getInputStream();
            assertEquals("HTTP/1.1 200 OK", line(input));
            headers(input);
            assertEquals("2", line(input));
            byte[] firstChunk = input.readNBytes(2);
            assertArrayEquals(new byte[] { 1, 2 }, firstChunk);
            assertEquals("", line(input));
            assertFalse(firstWrite.isDone());
            assertFalse(secondWrite.isDone());

            // 结束第一个响应后，重复 trailers 必须先于第二个响应出现在连接上。
            body.finish(Map.of());
            assertEquals("0", line(input));
            String firstTrailer = line(input).toLowerCase(Locale.ROOT);
            String secondTrailer = line(input).toLowerCase(Locale.ROOT);
            assertEquals("x-final: one", firstTrailer);
            assertEquals("x-final: two", secondTrailer);
            assertEquals("", line(input));
            assertEquals("HTTP/1.1 200 OK", line(input));
            headers(input);
            assertEquals(9, input.read());
            firstWrite.get(3, TimeUnit.SECONDS);
            secondWrite.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void jdkExpectContinueClientCanUploadWithoutWaitingForEof() throws Exception {
        // 以流式 SPI 回传上传正文，JDK 对端须先收到 100 Continue 才会发送正文。
        ConnectorConfig config = config(true);
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), channel -> new ChannelListener<>() {
                 public void connected() {
                 }

                 public void receive(HttpExchange exchange) {
                     // 头部已分发但上传尚未 EOF，响应直接使用正在接收的正文。
                     HttpBodyStream body = exchange.request().stream();
                     assertNotNull(body);
                     exchange.respond(HttpResponse.newStreaming(200, Map.of(), body, Map.of()));
                     channel.write(exchange);
                 }

                 public void closed(Throwable cause) {
                 }
             })) {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            URI uri = URI.create("http://127.0.0.1:" + listen.getAddress().getPort() + "/stream");
            var request = newBuilder(uri).expectContinue(true).timeout(Duration.ofSeconds(4)) //
                    .POST(BodyPublishers.ofString("continue-body")).build();

            // 上传和回传均在请求期限内完成，证明 100 Continue 没有与正文互相等待。
            var response = client.send(request, BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("continue-body", response.body());
        }
    }

    @Test(timeout = 15000)
    public void unsupportedExpectationIsRejectedBeforeApplicationDispatch() throws Exception {
        // 配置流式请求接收，用原始对端发送不支持的 Expect 字段。
        ConnectorConfig config = config(true);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(connected, requests)); //
             Socket peer = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            peer.setSoTimeout(3000);
            String request = "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nExpect: unknown\r\n\r\n";
            send(peer, request);

            // 传输返回 417 并关闭输入，应用接收队列始终没有这次请求。
            InputStream input = peer.getInputStream();
            assertEquals("HTTP/1.1 417 Expectation Failed", line(input));
            headers(input);
            assertEquals(-1, input.read());
            assertTrue(requests.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void completeResponseBodyReachesEofWhenPeerCloses() throws Exception {
        // 对端声明四字节正文，并等客户端读取后再关闭连接。
        ConnectorConfig config = config(true);
        ExecutorService io = Executors.newFixedThreadPool(2);
        CountDownLatch closePeer = new CountDownLatch(1);
        ClassLoader loader = this.getClass().getClassLoader();
        try (ServerSocket listener = new ServerSocket(0); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader)) {
            listener.setSoTimeout(3000);
            var peer = io.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream input = socket.getInputStream();
                    line(input);
                    headers(input);
                    send(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 4\r\n\r\ntail");
                    assertTrue(closePeer.await(3, TimeUnit.SECONDS));
                }
                return null;
            });
            InterAddress target = new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default");
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(target, routes(config), null, receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // 消费全部已声明正文，此时尚未允许对端主动关闭。
            HttpRequest request = HttpRequest.newBuffered("GET", "/", Map.of(), new byte[0], 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);
            channel.write(exchange).get(3, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream body = response.stream();
            assertNotNull(body);
            var reading = io.submit(() -> body.readNBytes(4));
            byte[] received = reading.get(3, TimeUnit.SECONDS);
            assertArrayEquals("tail".getBytes(StandardCharsets.US_ASCII), received);

            // 声明长度已经满足，关闭后读取正常 EOF，交换正常完成。
            closePeer.countDown();
            peer.get(3, TimeUnit.SECONDS);
            var eof = io.submit(() -> body.read());
            assertEquals(Integer.valueOf(-1), eof.get(3, TimeUnit.SECONDS));
            exchange.completion().get(3, TimeUnit.SECONDS);
        } finally {
            closePeer.countDown();
            io.shutdownNow();
            assertTrue(io.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void truncatedResponseBodyFailsWhenPeerCloses() throws Exception {
        // 对端声明六字节正文，只发送四字节，等待客户端读取后主动关闭。
        ConnectorConfig config = config(true);
        ExecutorService io = Executors.newFixedThreadPool(2);
        CountDownLatch closePeer = new CountDownLatch(1);
        ClassLoader loader = this.getClass().getClassLoader();
        try (ServerSocket listener = new ServerSocket(0); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader)) {
            listener.setSoTimeout(3000);
            var peer = io.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream input = socket.getInputStream();
                    line(input);
                    headers(input);
                    send(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 6\r\n\r\ntail");
                    assertTrue(closePeer.await(3, TimeUnit.SECONDS));
                }
                return null;
            });
            InterAddress target = new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default");
            BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
            client.connect(target, routes(config), null, receiver(connected, new LinkedBlockingQueue<>())) //
                    .get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);

            // 先消费已经收到的四字节，确保失败源自截断而非未收到响应。
            HttpRequest request = HttpRequest.newBuffered("GET", "/", Map.of(), new byte[0], 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);
            channel.write(exchange).get(3, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream body = response.stream();
            assertNotNull(body);
            var reading = io.submit(() -> body.readNBytes(4));
            byte[] received = reading.get(3, TimeUnit.SECONDS);
            assertArrayEquals("tail".getBytes(StandardCharsets.US_ASCII), received);

            // 未达到声明长度就关闭，正文读取和交换完成都必须传播 I/O 错误。
            closePeer.countDown();
            peer.get(3, TimeUnit.SECONDS);
            var remaining = io.submit(() -> body.read());
            ExecutionException bodyFailure = assertThrows(ExecutionException.class, () -> {
                remaining.get(3, TimeUnit.SECONDS);
            });
            assertTrue(bodyFailure.getCause() instanceof IOException);
            ExecutionException exchangeFailure = assertThrows(ExecutionException.class, () -> {
                exchange.completion().get(3, TimeUnit.SECONDS);
            });
            assertTrue(exchangeFailure.getCause() instanceof IOException);
        } finally {
            closePeer.countDown();
            io.shutdownNow();
            assertTrue(io.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void truncatedUploadFailsThePublishedBody() throws Exception {
        // 对端声明二十字节正文，只上传两个字节后关闭输出方向。
        ConnectorConfig config = config(true);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), receiver(connected, requests)); //
             Socket peer = new Socket("127.0.0.1", listen.getAddress().getPort())) {
            send(peer, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nab");
            HttpExchange exchange = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(exchange);
            HttpBodyStream body = exchange.request().stream();
            assertNotNull(body);
            var reading = consumer.submit(() -> body.readNBytes(2));
            byte[] received = reading.get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 'a', 'b' }, received);

            // 已发布给应用的正文必须收到截断错误，不能把半关闭当作正常 EOF。
            peer.shutdownOutput();
            var remaining = consumer.submit(() -> body.read());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                remaining.get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
        } finally {
            consumer.shutdownNow();
            assertTrue(consumer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static ChannelListenerFactory<HttpExchange> receiver(BasicFuture<NetworkChannel<HttpExchange>> connected, //
            LinkedBlockingQueue<HttpExchange> requests) {
        return channel -> new ChannelListener<>() {
            public void connected() {
                connected.completed(channel);
            }

            public void receive(HttpExchange exchange) {
                requests.add(exchange);
            }

            public void closed(Throwable cause) {
                connected.failed(cause);
            }
        };
    }

    private static ConnectorConfig config(boolean streaming) {
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        Map<String, String> options = Map.of("streamBody", String.valueOf(streaming));
        ProtocolConfig protocol = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, options, List.of(protocol), true);
    }

    private static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (value != '\r') {
                bytes.write(value);
            }
        }
        if (value == -1) {
            throw new IOException("Peer closed before expected HTTP line");
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void headers(InputStream input) throws IOException {
        while (!StringUtils.equals("", line(input))) {
            // 只跳过本场景无关的普通字段，正文和 trailers 在调用方逐项断言。
        }
    }

    private static String header(InputStream input, String name) throws IOException {
        String selected = null;
        String line;
        while (!StringUtils.equals("", line = line(input))) {
            int separator = line.indexOf(':');
            if (separator > 0 && StringUtils.equalsIgnoreCase(name, line.substring(0, separator))) {
                selected = line.substring(separator + 1).trim();
            }
        }
        return selected;
    }
}
