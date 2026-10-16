/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

/** gRPC-Web 的二进制、文本和响应流通过 TLS 后保持协议与调用语义。 */
public class GrpcWebTlsTest {
    @Test(timeout = 20000)
    public void binaryJsonUnaryUsesHttpsHttp1() throws Exception {
        // 配置 HTTP/1、服务端证书认证与二进制 gRPC-Web 消息。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        MetConfiguration outgoing = this.clientConfiguration(1, false, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅普通接口，调用不经过本地服务捷径。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 正文消息与状态尾帧都经过 HTTPS，返回字段不能受编码层影响。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("安全调用 中文", 7));
            assertEquals("安全调用 中文", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textProtobufUnaryUsesHttpsHttp1() throws Exception {
        // 配置 HTTP/1、服务端证书认证与文本 gRPC-Web 消息。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        MetConfiguration outgoing = this.clientConfiguration(1, true, null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅普通接口，调用不经过本地服务捷径。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 正文消息与状态尾帧都经过 HTTPS，返回字段不能受编码层影响。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("安全调用 中文", 7));
            assertEquals("安全调用 中文", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void binaryProtobufUnaryUsesMutualTlsHttp2() throws Exception {
        // 配置 HTTP/2、双向身份认证与二进制 gRPC-Web 消息。
        MetConfiguration incoming = this.serverConfiguration(2, "REQUIRE");
        MetConfiguration outgoing = this.clientConfiguration(2, false, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅普通接口，调用不经过本地服务捷径。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 正文消息与状态尾帧都经过 HTTPS，返回字段不能受编码层影响。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("安全调用 中文", 7));
            assertEquals("安全调用 中文", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textJsonUnaryUsesMutualTlsHttp2() throws Exception {
        // 配置 HTTP/2、双向身份认证与文本 gRPC-Web 消息。
        MetConfiguration incoming = this.serverConfiguration(2, "REQUIRE");
        MetConfiguration outgoing = this.clientConfiguration(2, true, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅普通接口，调用不经过本地服务捷径。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 正文消息与状态尾帧都经过 HTTPS，返回字段不能受编码层影响。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("安全调用 中文", 7));
            assertEquals("安全调用 中文", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp2WithAlpn() throws Exception {
        // 服务端只宣告 h2，独立 JDK 客户端发送标准 gRPC-Web 二进制帧。
        MetConfiguration incoming = this.serverConfiguration(2, "NONE");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 独立编码只使用公开的线协议，证书校验和 ALPN 由 JDK 客户端执行。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(GrpcWebTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_2).build();
            int port = server.getListenAddress("grpc-web").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/web/web.Echo/echo");
            byte[] body = this.binaryRequest("{\"value\":\"alpn\",\"count\":2}");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web+json") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 实际响应版本证明没有回退到 HTTP/1，消息后必须仍有成功状态尾帧。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            ByteBuffer frames = ByteBuffer.wrap(response.body());
            assertEquals(0, frames.get());
            byte[] payload = new byte[frames.getInt()];
            frames.get(payload);
            JsonNode result = new ObjectMapper().readTree(payload);
            assertEquals("alpn", result.path("value").asText());
            assertEquals(2, result.path("count").asInt());
            assertEquals(128, frames.get() & 0xff);
            byte[] trailers = new byte[frames.getInt()];
            frames.get(trailers);
            String metadata = new String(trailers, StandardCharsets.US_ASCII);
            assertTrue(metadata, metadata.contains("grpc-status: 0"));
            assertFalse(frames.hasRemaining());
        }
    }

    @Test(timeout = 20000)
    public void textBusinessFaultSurvivesHttps() throws Exception {
        // HTTPS 文本模式必须从正文尾帧恢复错误码与百分号转义的描述。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        MetConfiguration outgoing = this.clientConfiguration(1, true, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 从公开异步调用获取真正的协议异常，不把业务拒绝误认为 TLS 失败。
            MetClient remote = client.getRsfClient(server.getListenAddress("grpc-web"));
            Class<?>[] types = { Message.class };
            Object[] arguments = { Message.of("拒绝 中文 + %", 7) };
            MetFuture pending = remote.asyncInvoke(service, "fail", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(7, status.getCode());
            assertEquals("拒绝 中文 + %", status.getDescription());
        }
    }

    @Test(timeout = 20000)
    public void textHttpsResponseStreamHonorsSubscriberDemand() throws Exception {
        // HTTP/1 的分块正文与 TLS 共同承载文本响应流，用户逐条请求数据。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        MetConfiguration outgoing = this.clientConfiguration(1, true, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);

            // 首次只允许一条，观察窗口中不能收到未请求的第二条消息。
            StreamProbe subscriber = new StreamProbe();
            remote.list(Message.of("demand", 3)).subscribe(subscriber);
            assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
            subscriber.subscription.request(1);
            Message first = subscriber.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(0, first.getCount());
            assertNull(subscriber.messages.poll(100, TimeUnit.MILLISECONDS));

            // 再请求剩余数据，检查原有顺序和正文末尾的成功状态。
            subscriber.subscription.request(2);
            subscriber.finished.get(5, TimeUnit.SECONDS);
            Message second = subscriber.messages.poll(5, TimeUnit.SECONDS);
            Message third = subscriber.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            assertNotNull(third);
            assertEquals(1, second.getCount());
            assertEquals(2, third.getCount());
            assertTrue(subscriber.messages.isEmpty());
        }
    }

    @Test(timeout = 20000)
    public void cancellingBinaryMutualTlsStreamReleasesRemoteSource() throws Exception {
        // HTTP/2 双向认证下取消单个响应流，应通知服务端结束源而不关闭共享连接。
        MetConfiguration incoming = this.serverConfiguration(2, "REQUIRE");
        MetConfiguration outgoing = this.clientConfiguration(2, false, "client");
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebService implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message request) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        private boolean sent;

                        @Override
                        public void request(long count) {
                            if (!this.sent && count > 0) {
                                this.sent = true;
                                subscriber.onNext(request);
                            }
                        }

                        @Override
                        public void cancel() {
                            cancelled.countDown();
                        }
                    });
                };
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 源先发送一条结果，随后保持打开，确保取消时调用确实处于传输阶段。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, implementation) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            StreamProbe subscriber = new StreamProbe();
            remote.list(Message.of("cancel", 1)).subscribe(subscriber);
            assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
            subscriber.subscription.request(1);
            assertNotNull(subscriber.messages.poll(5, TimeUnit.SECONDS));

            // 取消必须到达远端源；另一次业务调用仍能使用该安全传输。
            subscriber.subscription.cancel();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            Message next = remote.echo(Message.of("after cancel", 2));
            assertEquals("after cancel", next.getValue());
        }
    }

    @Test(timeout = 20000)
    public void http2MutualTlsRejectsMissingClientCertificate() throws Exception {
        // 注册 gRPC-Web 服务，但在 HTTP/2 入站处理之前强制校验客户端证书。
        MetConfiguration incoming = this.serverConfiguration(2, "REQUIRE");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 独立 TLS 客户端信任服务端但没有身份，避免 HTTP 重试造成超时假阳性。
            SSLContext client = GrpcWebTlsUtils.clientContext("trust", null);
            int port = server.getListenAddress("grpc-web").getPort();
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);

                // TLS 1.3 拒绝可能晚于本地握手返回；读取不能收到任何 HTTP/2 应用字节。
                boolean rejected;
                try {
                    socket.startHandshake();
                    rejected = socket.getInputStream().read() < 0;
                } catch (IOException failure) {
                    boolean timedOut = failure instanceof SocketTimeoutException;
                    assertFalse("TLS rejection must not time out: " + failure, timedOut);
                    rejected = true;
                }
                assertTrue("Anonymous peer must not receive application data", rejected);
            }
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsUntrustedClientCertificate() throws Exception {
        // 客户端有证书，但其签发者不属于服务端配置的信任集。
        MetConfiguration incoming = this.serverConfiguration(1, "REQUIRE");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 以真实 gRPC-Web 请求触发握手，不能以 RPC 超时替代身份拒绝。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(GrpcWebTlsUtils.clientContext("trust", "untrusted-client")) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            int port = server.getListenAddress("grpc-web").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/web/web.Echo/echo");
            byte[] body = this.binaryRequest("{\"value\":\"rejected\",\"count\":1}");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web+json") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsUntrustedServerCertificate() throws Exception {
        // gRPC-Web 客户端必须遵守 CA 校验，不能因为 HTTP 适配而信任所有服务器。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 独立客户端只信任另外的 CA，发送合法业务帧也不能绕过验证。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(GrpcWebTlsUtils.clientContext("untrusted-trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            int port = server.getListenAddress("grpc-web").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/web/web.Echo/echo");
            byte[] body = this.binaryRequest("{\"value\":\"rejected\",\"count\":1}");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/grpc-web+json") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void incompatibleAlpnFailsBeforeRpcTimeout() throws Exception {
        // 服务端只接受 HTTP/1.1，客户端只接受 h2，配置必须在握手阶段失败。
        MetConfiguration incoming = this.serverConfiguration(1, "NONE");
        MetConfiguration outgoing = this.clientConfiguration(2, false, null);
        outgoing.setDefaultTimeout(10000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 公共 Caller 应提前收到传输错误，不能等到请求超时才结束。
            MetClient remote = client.getRsfClient(server.getListenAddress("grpc-web"));
            Class<?>[] types = { Message.class };
            Object[] arguments = { Message.of("mismatch", 1) };
            MetFuture pending = remote.asyncInvoke(service, "echo", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
        }
    }

    private MetConfiguration serverConfiguration(int version, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.setDefaultTimeout(5000);
        ConnectorSettings endpoint = configuration.connector("http").bind("127.0.0.1", 0);
        GrpcWebTlsUtils.serverTls(endpoint, version, clientAuth);
        endpoint.protocol("grpc-web").addressScheme("https").contextPath("/web") //
                .option("streamBody", true);
        return configuration;
    }

    private MetConfiguration clientConfiguration(int version, boolean text, String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.setDefaultTimeout(5000);
        ConnectorSettings endpoint = configuration.connector("http").option("httpVersion", version);
        GrpcWebTlsUtils.clientTls(endpoint, version, identity);
        endpoint.protocol("grpc-web").addressScheme("https").contextPath("/web") //
                .option("streamBody", true).option("webText", text);
        return configuration;
    }

    private byte[] binaryRequest(String json) {
        byte[] message = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(message.length + 5).put((byte) 0).putInt(message.length).put(message).array();
    }

    private static final class StreamProbe implements Flow.Subscriber<Message> {
        private final CountDownLatch         subscribed = new CountDownLatch(1);
        private final BlockingQueue<Message> messages   = new LinkedBlockingQueue<>();
        private final BasicFuture<Void>      finished   = new BasicFuture<>();
        private       Flow.Subscription      subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscribed.countDown();
        }

        @Override
        public void onNext(Message item) {
            this.messages.add(item);
        }

        @Override
        public void onError(Throwable failure) {
            this.finished.failed(failure);
        }

        @Override
        public void onComplete() {
            this.finished.completed(null);
        }
    }
}
