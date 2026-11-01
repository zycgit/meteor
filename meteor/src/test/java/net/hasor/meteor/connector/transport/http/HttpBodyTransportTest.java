/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import static net.hasor.meteor.connector.HttpTransportPlans.routes;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.TransportTestServices.newHttpConnector;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 同一组消息契约分别通过真实 HTTP/1 和 HTTP/2 连接验证。 */
@ParameterizedClass(name = "HTTP/{0}", autoCloseArguments = false, quoteTextArguments = false)
@MethodSource("versions")
public class HttpBodyTransportTest {
    public static Object[] versions() {
        return new Object[] { 1, 2 };
    }

    @Parameter(0)
    public int version;

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void incrementalEchoPublishesHeadersAndFirstBytesBeforeUploadEof() throws Exception {
        // 正文读取使用独立执行器，让没有到达的 EOF 也有明确等待期限。
        ExecutorService consumer = Executors.newSingleThreadExecutor();

        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            HttpBodyStream output = new HttpBodyStream(16384);
            Map<String, List<String>> headers = Map.of("x-tag", List.of("first", "second"));
            HttpRequest request = HttpRequest.newStreaming("POST", "/stream", headers, output, this.version);
            HttpExchange outgoing = new HttpExchange(request);
            Future<Void> writing = outgoingChannel.write(outgoing);

            // 请求头到达即交付，响应也无需等待上传结束。
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertEquals(List.of("first", "second"), incoming.request().headers().get("X-TAG"));
            HttpBodyStream received = incoming.request().stream();
            assertNotNull(received);
            List<String> cookieValues = List.of("a=1; Expires=Wed, 21 Oct 2030 07:28:00 GMT", "b=2; Path=/");
            Map<String, List<String>> cookies = Map.of("set-cookie", cookieValues);
            incoming.respond(HttpResponse.newStreaming(200, cookies, received, Map.of()));
            Future<Void> replying = serverReceiver.connected.get(3, TimeUnit.SECONDS).write(incoming);

            OutputStream stream = output.output();
            stream.write("head".getBytes(StandardCharsets.UTF_8));
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            assertEquals(cookies.get("set-cookie"), response.headers().get("SET-COOKIE"));
            byte[] head = consumer.submit(() -> response.stream().readNBytes(4)).get(3, TimeUnit.SECONDS);
            assertArrayEquals("head".getBytes(StandardCharsets.UTF_8), head);
            assertFalse(writing.isDone());
            assertFalse(replying.isDone());

            // 结束正文后，重复尾部字段同样按原值抵达对端。
            Map<String, List<String>> trailers = Map.of("x-check", List.of("part-one", "part-two"));
            output.finish(trailers);
            var eofRead = consumer.submit(() -> {
                return response.stream().read();
            });
            int eof = eofRead.get(3, TimeUnit.SECONDS);
            assertEquals(-1, eof);
            assertEquals(trailers, response.stream().trailers());
            writing.get(3, TimeUnit.SECONDS);
            replying.get(3, TimeUnit.SECONDS);
            outgoing.completion().get(3, TimeUnit.SECONDS);
            assertTrue(outgoingChannel.isOpen());
        } finally {
            consumer.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void streamingEchoTransfersBodyLargerThanTheFlowWindow() throws Exception {
        // 生产者与消费端并行，避免窗口背压阻塞测试线程。
        ExecutorService producer = Executors.newSingleThreadExecutor();

        // 正文读取使用独立执行器，让没有到达的 EOF 也有明确等待期限。
        ExecutorService consumer = Executors.newSingleThreadExecutor();

        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            // 服务端将收到的正文直接作为响应生产源，双方必须同时推进窗口。
            HttpBodyStream output = new HttpBodyStream(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/large", Map.of(), output, this.version);
            HttpExchange outgoing = new HttpExchange(request);
            Future<Void> writing = outgoingChannel.write(outgoing);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            HttpBodyStream received = incoming.request().stream();
            assertNotNull(received);
            incoming.respond(HttpResponse.newStreaming(200, Map.of(), received, Map.of()));
            Future<Void> replying = serverReceiver.connected.get(3, TimeUnit.SECONDS).write(incoming);
            OutputStream stream = output.output();

            // 正文远大于收发窗口；边生产、边消费，末尾字段保留每个值。
            byte[] payload = new byte[512000];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
            Map<String, List<String>> trailers = Map.of("x-check", List.of("part-one", "part-two"));
            var produced = producer.submit(() -> {
                stream.write(payload);
                output.finish(trailers);
                return null;
            });
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            byte[] echoed = consumer.submit(response.stream()::readAllBytes).get(3, TimeUnit.SECONDS);
            assertArrayEquals(payload, echoed);
            assertEquals(trailers, response.stream().trailers());
            produced.get(3, TimeUnit.SECONDS);
            writing.get(3, TimeUnit.SECONDS);
            replying.get(3, TimeUnit.SECONDS);
            outgoing.completion().get(3, TimeUnit.SECONDS);
            assertTrue(outgoingChannel.isOpen());
        } finally {
            consumer.shutdownNow();
            producer.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void bufferedMessagesRetainRepeatedHeadersAndTrailers() throws Exception {
        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(false);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            Map<String, List<String>> headers = Map.of("x-tag", List.of("first", "second"));
            Map<String, List<String>> trailers = Map.of("x-check", List.of("one", "two"));
            byte[] bytes = "buffered".getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuffered("POST", "/buffered", headers, bytes, this.version, trailers);
            HttpExchange outgoing = new HttpExchange(request);
            outgoingChannel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertEquals(headers.get("x-tag"), incoming.request().headers().get("X-TAG"));
            assertEquals(trailers, incoming.request().trailers());
            assertArrayEquals(bytes, incoming.request().body());

            // 缓冲响应同样保存重复字段，不拆分值中的内容。
            Map<String, List<String>> cookies = Map.of("set-cookie", List.of("a=1", "b=2"));
            incoming.respond(HttpResponse.newBuffered(200, cookies, bytes, trailers));
            serverReceiver.connected.get(3, TimeUnit.SECONDS).write(incoming).get(3, TimeUnit.SECONDS);
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            assertEquals(cookies.get("set-cookie"), response.headers().get("Set-Cookie"));
            assertEquals(trailers, response.trailers());
            assertArrayEquals(bytes, response.body());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void bufferedRequestCanReceiveAnIncrementalResponse() throws Exception {
        // 正文读取使用独立执行器，让没有到达的 EOF 也有明确等待期限。
        ExecutorService consumer = Executors.newSingleThreadExecutor();

        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            byte[] emptyBody = new byte[0];
            Map<String, List<String>> fields = Map.of();
            HttpRequest request = HttpRequest.newBuffered("GET", "/stream", fields, emptyBody, this.version, fields);
            HttpExchange outgoing = new HttpExchange(request);
            outgoingChannel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            HttpBodyStream requestBody = incoming.request().stream();
            byte[] receivedBody = consumer.submit(requestBody::readAllBytes).get(3, TimeUnit.SECONDS);
            assertEquals(0, receivedBody.length);

            // 缓冲请求结束后，响应逐块产生，静态和动态尾部字段共同保留。
            HttpBodyStream body = new HttpBodyStream(16);
            Map<String, List<String>> staticTrailers = Map.of("x-static", List.of("a", "b"));
            incoming.respond(HttpResponse.newStreaming(200, Map.of(), body, staticTrailers));
            var writing = serverReceiver.connected.get(3, TimeUnit.SECONDS).write(incoming);
            body.output().write(7);
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            assertNotNull(response.stream());
            var firstRead = consumer.submit(() -> {
                return response.stream().read();
            });
            int firstByte = firstRead.get(3, TimeUnit.SECONDS);
            assertEquals(7, firstByte);
            assertFalse(writing.isDone());
            body.finish(Map.of("x-dynamic", List.of("c", "d")));
            var eofRead = consumer.submit(() -> {
                return response.stream().read();
            });
            int eof = eofRead.get(3, TimeUnit.SECONDS);
            assertEquals(-1, eof);
            assertEquals(List.of("a", "b"), response.stream().trailers().get("X-STATIC"));
            assertEquals(List.of("c", "d"), response.stream().trailers().get("X-DYNAMIC"));
            outgoing.completion().get(3, TimeUnit.SECONDS);
        } finally {
            consumer.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void failedResponseProducerTerminatesThePublishedBody() throws Exception {
        // 正文读取使用独立执行器，让没有到达的 EOF 也有明确等待期限。
        ExecutorService consumer = Executors.newSingleThreadExecutor();

        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            byte[] emptyBody = new byte[0];
            Map<String, List<String>> fields = Map.of();
            HttpRequest request = HttpRequest.newBuffered("GET", "/failure", fields, emptyBody, this.version, fields);
            HttpExchange outgoing = new HttpExchange(request);
            outgoingChannel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            HttpBodyStream requestBody = incoming.request().stream();
            byte[] receivedBody = consumer.submit(requestBody::readAllBytes).get(3, TimeUnit.SECONDS);
            assertEquals(0, receivedBody.length);

            // 首块已到应用后再让生产者失败，读取者收到异常，不能把中断误作 EOF。
            HttpBodyStream body = new HttpBodyStream(16);
            incoming.respond(HttpResponse.newStreaming(200, Map.of(), body, Map.of()));
            var writing = serverReceiver.connected.get(3, TimeUnit.SECONDS).write(incoming);
            body.output().write(7);
            HttpResponse published = outgoing.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream response = published.stream();
            var firstRead = consumer.submit(() -> {
                return response.read();
            });
            int firstByte = firstRead.get(3, TimeUnit.SECONDS);
            assertEquals(7, firstByte);
            body.fail(new IOException("response source failed"));
            assertThrows(ExecutionException.class, () -> writing.get(3, TimeUnit.SECONDS));
            ExecutionException readFailure = assertThrows(ExecutionException.class, () -> {
                var remaining = consumer.submit(() -> {
                    return response.read();
                });
                remaining.get(3, TimeUnit.SECONDS);
            });
            assertTrue(readFailure.getCause() instanceof IOException);
            assertThrows(ExecutionException.class, () -> outgoing.completion().get(3, TimeUnit.SECONDS));
        } finally {
            consumer.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancelledUploadReleasesProducerAndAllowsAnotherExchange() throws Exception {
        // 生产者与消费端并行，避免窗口背压阻塞测试线程。
        ExecutorService producer = Executors.newSingleThreadExecutor();

        // 正文读取使用独立执行器，让没有到达的 EOF 也有明确等待期限。
        ExecutorService consumer = Executors.newSingleThreadExecutor();

        // 显式创建并连接两端 HTTP 传输，协议版本只影响无资源配置。
        ConnectorConfig config = this.config(true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect(null, listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> outgoingChannel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            HttpBodyStream output = new HttpBodyStream(16384);
            HttpRequest request = HttpRequest.newStreaming("POST", "/cancel", Map.of(), output, this.version);
            HttpExchange outgoing = new HttpExchange(request);
            Future<Void> writing = outgoingChannel.write(outgoing);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 消费端故意不读；取消必须唤醒可能阻塞的生产者并终止这一次交换。
            CountDownLatch producerStarted = new CountDownLatch(1);
            var produced = producer.submit(() -> {
                producerStarted.countDown();
                output.output().write(new byte[8 * 1024 * 1024]);
                return null;
            });
            assertTrue(producerStarted.await(3, TimeUnit.SECONDS));
            outgoing.complete(new IOException("cancel upload"));
            assertThrows(ExecutionException.class, () -> produced.get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> writing.get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> outgoing.completion().get(3, TimeUnit.SECONDS));

            // HTTP/1 重建物理连接，HTTP/2 保留连接并分配新 stream；调用者都能继续使用通道。
            HttpBodyStream nextBody = new HttpBodyStream(16);
            nextBody.output().write(7);
            nextBody.finish(Map.of());
            HttpRequest next = HttpRequest.newStreaming("POST", "/next", Map.of(), nextBody, this.version);
            HttpExchange following = new HttpExchange(next);
            outgoingChannel.write(following).get(3, TimeUnit.SECONDS);
            HttpExchange arrival = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(arrival);
            HttpBodyStream nextRequest = arrival.request().stream();
            byte[] arrived = consumer.submit(nextRequest::readAllBytes).get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 7 }, arrived);
            arrival.respond(HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of()));
            serverReceiver.latest.get().write(arrival).get(3, TimeUnit.SECONDS);
            HttpResponse response = following.response().get(3, TimeUnit.SECONDS);
            byte[] responseBytes = consumer.submit(response.stream()::readAllBytes).get(3, TimeUnit.SECONDS);
            assertEquals(0, responseBytes.length);
            following.completion().get(3, TimeUnit.SECONDS);
        } finally {
            consumer.shutdownNow();
            producer.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedProtocolEnablesStreamingResponsesOverBufferedEndpoint() throws Exception {
        // 端点默认聚合，选中的第二个协议单独启用流式响应。
        ConnectorConfig config = this.bodyModeConfig(false, true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect("selected", listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            // 使用普通请求和完整响应，避免请求本身的流式正文影响响应模式。
            HttpRequest request = HttpRequest.newBuffered("GET", "/selected", Map.of(), //
                    new byte[0], this.version, Map.of());
            HttpExchange outgoing = new HttpExchange(request);
            channel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            byte[] bytes = new byte[] { 1, 2, 3 };
            incoming.respond(HttpResponse.newBuffered(200, Map.of(), bytes, Map.of()));
            NetworkChannel<HttpExchange> accepted = serverReceiver.connected.get(3, TimeUnit.SECONDS);
            accepted.write(incoming).get(3, TimeUnit.SECONDS);

            // 响应以流交付，正文完整且读取受等待期限约束。
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            HttpBodyStream body = response.stream();
            assertNotNull(body);
            byte[] actual = consumer.submit(body::readAllBytes).get(3, TimeUnit.SECONDS);
            assertArrayEquals(bytes, actual);
        } finally {
            consumer.shutdownNow();
            assertTrue(consumer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedProtocolDisablesStreamingResponsesOverStreamingEndpoint() throws Exception {
        // 端点默认流式，选中的第二个协议明确要求聚合响应。
        ConnectorConfig config = this.bodyModeConfig(true, false);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect("selected", listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = clientReceiver.connected.get(3, TimeUnit.SECONDS);

            // 完整响应必须按协议选项聚合，不沿用端点或第一个协议的流式设置。
            HttpRequest request = HttpRequest.newBuffered("GET", "/selected", Map.of(), //
                    new byte[0], this.version, Map.of());
            HttpExchange outgoing = new HttpExchange(request);
            channel.write(outgoing).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            byte[] bytes = new byte[] { 4, 5, 6 };
            incoming.respond(HttpResponse.newBuffered(200, Map.of(), bytes, Map.of()));
            NetworkChannel<HttpExchange> accepted = serverReceiver.connected.get(3, TimeUnit.SECONDS);
            accepted.write(incoming).get(3, TimeUnit.SECONDS);

            // 对调用方交付完整字节数组，不创建增量正文流。
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            assertNull(response.stream());
            assertArrayEquals(bytes, response.body());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedRouteEnablesStreamingRequestsOverBufferedEndpoint() throws Exception {
        // 端点默认聚合，只有请求路径选中的协议开启增量正文。
        ConnectorConfig config = this.bodyModeConfig(false, true);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect("other", listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = clientReceiver.connected.get(3, TimeUnit.SECONDS);
            HttpBodyStream upload = new HttpBodyStream(16);
            HttpRequest request = HttpRequest.newStreaming("POST", "/selected", Map.of(), upload, this.version);
            HttpExchange outgoing = new HttpExchange(request);
            Future<Void> writing = channel.write(outgoing);

            // 未结束上传也能拿到正文流，入站模式按路径确定，不沿用出站协议。
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            HttpBodyStream body = incoming.request().stream();
            assertNotNull(body);
            assertFalse(writing.isDone());
            byte[] bytes = new byte[] { 1, 2, 3 };
            upload.output().write(bytes);
            byte[] actual = consumer.submit(() -> body.readNBytes(bytes.length)).get(3, TimeUnit.SECONDS);
            assertArrayEquals(bytes, actual);
            upload.finish(Map.of());
            var eof = consumer.submit(() -> {
                return body.read();
            });
            assertEquals(Integer.valueOf(-1), eof.get(3, TimeUnit.SECONDS));
            writing.get(3, TimeUnit.SECONDS);

            // 完成本次响应，让上传和下载的生命周期均正常结束。
            incoming.respond(HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of()));
            NetworkChannel<HttpExchange> accepted = serverReceiver.connected.get(3, TimeUnit.SECONDS);
            accepted.write(incoming).get(3, TimeUnit.SECONDS);
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            byte[] responseBytes = consumer.submit(response.stream()::readAllBytes).get(3, TimeUnit.SECONDS);
            assertEquals(0, responseBytes.length);
            outgoing.completion().get(3, TimeUnit.SECONDS);
        } finally {
            consumer.shutdownNow();
            assertTrue(consumer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedRouteDisablesStreamingRequestsOverStreamingEndpoint() throws Exception {
        // 端点默认流式，只有请求路径选中的协议要求先聚合完整正文。
        ConnectorConfig config = this.bodyModeConfig(true, false);
        Recorder serverReceiver = new Recorder();
        Recorder clientReceiver = new Recorder();
        ClassLoader loader = this.getClass().getClassLoader();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try (NetworkConnector<HttpExchange> server = newHttpConnector(transport(config), loader); //
             NetworkConnector<HttpExchange> client = newHttpConnector(transport(config), loader); //
             NetworkListen listen = server.bind(config.address(), routes(config), serverReceiver)) {
            client.connect("other", listen.getAddress(), routes(config), clientReceiver).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = clientReceiver.connected.get(3, TimeUnit.SECONDS);
            HttpBodyStream upload = new HttpBodyStream(16);
            byte[] bytes = new byte[] { 4, 5, 6 };
            upload.output().write(bytes);
            HttpRequest request = HttpRequest.newStreaming("POST", "/selected", Map.of(), upload, this.version);
            HttpExchange outgoing = new HttpExchange(request);
            Future<Void> writing = channel.write(outgoing);

            // 正文结束前不向应用发布；完成后交付聚合字节数组。
            assertNull(serverReceiver.requests.poll(100, TimeUnit.MILLISECONDS));
            upload.finish(Map.of());
            writing.get(3, TimeUnit.SECONDS);
            HttpExchange incoming = serverReceiver.requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertNull(incoming.request().stream());
            assertArrayEquals(bytes, incoming.request().body());

            // 结束本次响应，确认模式选择没有干扰正常收发和连接复用。
            incoming.respond(HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of()));
            NetworkChannel<HttpExchange> accepted = serverReceiver.connected.get(3, TimeUnit.SECONDS);
            accepted.write(incoming).get(3, TimeUnit.SECONDS);
            HttpResponse response = outgoing.response().get(3, TimeUnit.SECONDS);
            byte[] responseBytes = consumer.submit(response.stream()::readAllBytes).get(3, TimeUnit.SECONDS);
            assertEquals(0, responseBytes.length);
            outgoing.completion().get(3, TimeUnit.SECONDS);
        } finally {
            consumer.shutdownNow();
            assertTrue(consumer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private ConnectorConfig bodyModeConfig(boolean endpointStreaming, boolean protocolStreaming) {
        Map<String, String> options = Map.of(//
                "httpVersion", String.valueOf(this.version), "streamBody", String.valueOf(endpointStreaming));
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        Map<String, String> otherOptions = Map.of(//
                "contextPath", "/other", "streamBody", String.valueOf(!protocolStreaming));
        Map<String, String> selectedOptions = Map.of(//
                "contextPath", "/selected", "streamBody", String.valueOf(protocolStreaming));

        // 前一个协议使用相反模式，确认按名称选择而不是取列表首项。
        List<ProtocolConfig> protocols = new ArrayList<>();
        protocols.add(ProtocolConfig.newConfig("other", "http", otherOptions));
        protocols.add(ProtocolConfig.newConfig("selected", "http", selectedOptions));
        return new ConnectorConfig("http", address, options, protocols, true);
    }

    private ConnectorConfig config(boolean streaming) {
        Map<String, String> values = new HashMap<>();
        values.put("httpVersion", String.valueOf(this.version));
        values.put("streamBody", String.valueOf(streaming));
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, values, List.of(protocol), true);
    }

    private static final class Recorder implements ChannelListenerFactory<HttpExchange> {
        private final BasicFuture<NetworkChannel<HttpExchange>>     connected = new BasicFuture<>();
        private final AtomicReference<NetworkChannel<HttpExchange>> latest    = new AtomicReference<>();
        private final LinkedBlockingQueue<HttpExchange>             requests  = new LinkedBlockingQueue<>();

        public ChannelListener<HttpExchange> createListener(NetworkChannel<HttpExchange> channel) {
            return new ChannelListener<HttpExchange>() {
                public void connected() {
                    Recorder.this.latest.set(channel);
                    Recorder.this.connected.completed(channel);
                }

                public void receive(HttpExchange exchange) {
                    Recorder.this.requests.add(exchange);
                }

                public void closed(Throwable cause) {
                    Recorder.this.connected.failed(cause);
                }
            };
        }
    }
}
