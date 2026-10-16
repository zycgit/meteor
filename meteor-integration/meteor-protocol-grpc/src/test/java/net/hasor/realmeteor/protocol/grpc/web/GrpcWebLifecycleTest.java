/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebLifecycleTest {
    @Test(timeout = 20000)
    public void independentHttp2ClientMultiplexesCallsOnOneConnection() throws Exception {
        // 独立 HTTP/2 客户端复用连接；Meteor 服务端仍通过 bootstrap 发布普通 Java 接口。
        MetConfiguration incoming = this.incoming();
        Set<InterAddress> peers = ConcurrentHashMap.newKeySet();
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher().bindFilter("observe-peer", (request, response, chain) -> {
                peers.add(request.getRemoteAddress());
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 先完成 h2c 协商，再在同一个 JDK 客户端上并发提交数据帧。
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            ObjectMapper mapper = new ObjectMapper();
            byte[] initial = mapper.writeValueAsBytes(Message.of("ready", 0));
            URI uri = this.uri(server, "echo");
            HttpRequest first = this.post(uri, this.frame(0, initial));
            HttpResponse<byte[]> ready = http.send(first, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(HttpClient.Version.HTTP_2, ready.version());
            assertEquals(200, ready.statusCode());
            List<Future<HttpResponse<byte[]>>> pending = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                byte[] message = mapper.writeValueAsBytes(Message.of("call-" + index, index));
                HttpRequest request = this.post(uri, this.frame(0, message));
                pending.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
            }

            // 每条 HTTP/2 stream 保留自己的消息和状态，服务观察到的客户端 socket 只有一个。
            for (int index = 0; index < pending.size(); index++) {
                HttpResponse<byte[]> response = pending.get(index).get(5, TimeUnit.SECONDS);
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(200, response.statusCode());
                ByteBuffer frames = ByteBuffer.wrap(response.body());
                assertEquals(0, Byte.toUnsignedInt(frames.get()));
                byte[] message = new byte[frames.getInt()];
                frames.get(message);
                JsonNode value = mapper.readTree(message);
                assertEquals("call-" + index, value.path("value").asText());
                assertEquals(index, value.path("count").asInt());
                assertEquals(0x80, Byte.toUnsignedInt(frames.get()));
                assertTrue(this.trailers(frames).contains("grpc-status: 0\r\n"));
                assertFalse(frames.hasRemaining());
            }
            assertEquals(1, peers.size());
        }
    }

    @Test(timeout = 20000)
    public void closingTheHttp2ClientCancelsTheRemotePublisherBeforeItsDeadline() throws Exception {
        // 使用长于断言等待时间的期限，排除依靠正常超时回收的假成功。
        MetConfiguration incoming = this.incoming().setDefaultTimeout(15000);
        MetConfiguration outgoing = this.outgoing(2).setDefaultTimeout(15000);
        CountDownLatch producing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebServiceImpl implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message request) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                        }

                        @Override
                        public void cancel() {
                            cancelled.countDown();
                        }
                    });
                    producing.countDown();
                };
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, implementation) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").timeout(15000).register();

            // 确认服务端开始响应流后关闭调用方的公开上下文。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            var completion = MetCaller.consume(remote.list(Message.of("waiting", 1)), value -> {
                fail("The controlled publisher must not emit messages");
            });
            assertTrue(producing.await(5, TimeUnit.SECONDS));
            client.close();

            // 本地流结束且远端被取消，整个过程发生在 15 秒调用期限之前。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                completion.get(3, TimeUnit.SECONDS);
            });
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 20000)
    public void gzipRequestAndResponseKeepTheirCompressedFrameFlag() throws Exception {
        // 服务选择 gRPC gzip；独立 HTTP 对端直接验证实际帧，避免仅检查配置值。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true).option("compression", "gzip");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // JSON 消息先经 JDK gzip 压缩，再放入压缩标记为 1 的 gRPC-Web 数据帧。
            String value = "gzip 中文".repeat(4096);
            byte[] json = new ObjectMapper().writeValueAsBytes(Message.of(value, 7));
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(json);
            }
            byte[] body = this.frame(1, compressed.toByteArray());
            URI uri = this.uri(server, "echo");
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web+json") //
                    .header("grpc-encoding", "gzip") //
                    .header("grpc-accept-encoding", "gzip") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 不经过插件解码器，读取响应压缩标记、gzip 内容与独立的成功尾帧。
            assertEquals(200, response.statusCode());
            assertEquals("gzip", response.headers().firstValue("grpc-encoding").orElse(null));
            ByteBuffer frames = ByteBuffer.wrap(response.body());
            assertEquals(1, Byte.toUnsignedInt(frames.get()));
            int length = frames.getInt();
            byte[] encoded = new byte[length];
            frames.get(encoded);
            assertTrue(encoded.length < json.length);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
                JsonNode result = new ObjectMapper().readTree(gzip);
                assertEquals(value, result.path("value").asText());
                assertEquals(7, result.path("count").asInt());
            }
            assertEquals(0x80, Byte.toUnsignedInt(frames.get()));
            assertTrue(this.trailers(frames).contains("grpc-status: 0\r\n"));
            assertFalse(frames.hasRemaining());
        }
    }

    @Test(timeout = 20000)
    public void h2cUpgradeRetainsTheGrpcWebDataAndTrailerFrames() throws Exception {
        // JDK HTTP/2 客户端从 HTTP/1 Upgrade 建立 h2c，不使用 prior-knowledge 连接。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 升级请求携带普通 gRPC-Web 二进制正文，服务只看自己的协议格式。
            byte[] json = "{\"value\":\"h2c\",\"count\":3}".getBytes(StandardCharsets.UTF_8);
            URI uri = this.uri(server, "echo");
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web+json") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.frame(0, json))) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 实际响应版本确认 h2c；正常数据帧之后仍有 gRPC-Web 状态尾帧。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            ByteBuffer frames = ByteBuffer.wrap(response.body());
            assertEquals(0, Byte.toUnsignedInt(frames.get()));
            byte[] message = new byte[frames.getInt()];
            frames.get(message);
            JsonNode result = new ObjectMapper().readTree(message);
            assertEquals("h2c", result.path("value").asText());
            assertEquals(3, result.path("count").asInt());
            assertEquals(0x80, Byte.toUnsignedInt(frames.get()));
            assertTrue(this.trailers(frames).contains("grpc-status: 0\r\n"));
            assertFalse(frames.hasRemaining());
        }
    }

    @Test(timeout = 20000)
    public void twoTextRequestMessagesAreRejectedBeforeDispatchingTheFirst() throws Exception {
        // 服务记录是否执行，证明请求流拒绝发生在 Dispatcher 入队之前。
        MetConfiguration configuration = this.incoming();
        AtomicInteger invoked = new AtomicInteger();
        GrpcWebServiceImpl implementation = new GrpcWebServiceImpl() {
            @Override
            public Message echo(Message request) {
                invoked.incrementAndGet();
                return request;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, implementation) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 两个合法消息帧组成一个非法客户端流；文本层只负责 Base64 封装。
            byte[] json = "{\"value\":\"forbidden\",\"count\":1}".getBytes(StandardCharsets.UTF_8);
            byte[] message = this.frame(0, json);
            ByteBuffer multiple = ByteBuffer.allocate(message.length * 2);
            multiple.put(message).put(message);
            byte[] text = Base64.getEncoder().encode(multiple.array());
            URI uri = this.uri(server, "echo");
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web-text+json") //
                    .header("Accept", "application/grpc-web-text+json") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(text)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 只返回错误尾帧，不能产生业务响应，也不能已经执行第一条消息。
            assertEquals(200, response.statusCode());
            ByteBuffer frames = ByteBuffer.wrap(Base64.getDecoder().decode(response.body()));
            assertEquals(0x80, Byte.toUnsignedInt(frames.get()));
            assertTrue(this.trailers(frames).contains("grpc-status: 13\r\n"));
            assertFalse(frames.hasRemaining());
            assertEquals(0, invoked.get());
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        return configuration;
    }

    private MetConfiguration outgoing(int version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").option("httpVersion", version).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        return configuration;
    }

    private URI uri(MetContext server, String method) {
        int port = server.getListenAddress("grpc-web").getPort();
        return URI.create("http://127.0.0.1:" + port + "/web/web.Echo/" + method);
    }

    private HttpRequest post(URI uri, byte[] body) {
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/grpc-web+json") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)) //
                .build();
    }

    private byte[] frame(int flag, byte[] body) {
        ByteBuffer frame = ByteBuffer.allocate(5 + body.length);
        frame.put((byte) flag).putInt(body.length).put(body);
        return frame.array();
    }

    private String trailers(ByteBuffer frames) {
        byte[] block = new byte[frames.getInt()];
        frames.get(block);
        return new String(block, StandardCharsets.US_ASCII);
    }
}
