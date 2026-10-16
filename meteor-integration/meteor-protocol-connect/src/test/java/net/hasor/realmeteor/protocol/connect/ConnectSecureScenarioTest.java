/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** Connect 的 HTTP 版本、TLS 身份与协商结果通过公开启动和发布入口验证。 */
public class ConnectSecureScenarioTest {
    @Test(timeout = 20000)
    public void jsonUnaryUsesPlainHttp1() throws Exception {
        // JSON 单次调用使用 HTTP/1，客户端不监听端口。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void jsonUnaryUsesHttp2PriorKnowledge() throws Exception {
        // JSON 单次调用使用 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void jsonUnaryUsesHttpsHttp1ServerAuthentication() throws Exception {
        // HTTP/1 HTTPS 的客户端严格验证服务端证书。
        MetConfiguration incoming = this.secureIncoming(1, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(1, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void jsonUnaryUsesHttpsHttp1MutualAuthentication() throws Exception {
        // HTTP/1 HTTPS 在发送 JSON 调用前校验双方证书。
        MetConfiguration incoming = this.secureIncoming(1, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(1, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void jsonUnaryUsesHttpsHttp2ServerAuthentication() throws Exception {
        // HTTP/2 HTTPS 在 h2 ALPN 协商后传递 JSON 调用。
        MetConfiguration incoming = this.secureIncoming(2, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void jsonUnaryUsesHttpsHttp2MutualAuthentication() throws Exception {
        // HTTP/2 HTTPS 的 h2 协商与双向证书认证共同承载 JSON 调用。
        MetConfiguration incoming = this.secureIncoming(2, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json") //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void protobufUnaryUsesHttpsHttp1ServerAuthentication() throws Exception {
        // Protobuf 使用 HTTP/1 HTTPS，与 TLS 相互独立。
        MetConfiguration incoming = this.secureIncoming(1, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(1, null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void protobufUnaryUsesHttpsHttp1MutualAuthentication() throws Exception {
        // Protobuf 使用 HTTP/1 HTTPS 并校验客户端证书。
        MetConfiguration incoming = this.secureIncoming(1, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(1, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void protobufUnaryUsesHttpsHttp2ServerAuthentication() throws Exception {
        // Protobuf 使用 h2 ALPN 与服务端证书认证。
        MetConfiguration incoming = this.secureIncoming(2, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void protobufUnaryUsesHttpsHttp2MutualAuthentication() throws Exception {
        // Protobuf 在 HTTP/2 双向 TLS 后保持原有消息字段。
        MetConfiguration incoming = this.secureIncoming(2, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动以后发布与订阅服务，客户端没有本地实现可供直接调用。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过公开代理完成真实网络调用，验证消息经过对应传输后保持业务语义。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            ConnectMessage response = remote.echo(ConnectMessage.of("Connect 安全调用", 7));
            assertEquals("Connect 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void independentClientUpgradesConnectCallToH2c() throws Exception {
        // 明文监听在同一端口接收 HTTP/1 Upgrade，独立客户端随后使用 HTTP/2。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 请求只按公开 Connect JSON 格式编码，不调用插件内部编解码器。
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            URI uri = this.uri(server, "http", "echo");
            HttpRequest request = this.unaryRequest(uri, "h2c");
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 实际响应版本证明完成升级，同时验证 JSON 业务结果。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            JsonNode message = new ObjectMapper().readTree(response.body());
            assertEquals("h2c", message.path("text").asText());
            assertEquals(8, message.path("number").asInt());
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp2WithAlpn() throws Exception {
        // 服务端仅宣告 h2，独立 JDK 客户端必须通过 ALPN 选定 HTTP/2。
        MetConfiguration configuration = this.secureIncoming(2, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 客户端严格验证测试 CA，Connect 正文采用公开 JSON 表示。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(ConnectTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_2).build();
            URI uri = this.uri(server, "https", "echo");
            HttpResponse<byte[]> response = client.send(this.unaryRequest(uri, "alpn"), //
                    HttpResponse.BodyHandlers.ofByteArray());

            // 不允许回退到 HTTP/1；协商结果与业务数据分别验证。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            JsonNode message = new ObjectMapper().readTree(response.body());
            assertEquals("alpn", message.path("text").asText());
            assertEquals(8, message.path("number").asInt());
        }
    }

    @Test(timeout = 20000)
    public void http2MutualTlsRejectsMissingClientCertificate() throws Exception {
        // Connect 挂载在要求客户端身份的 h2 HTTPS 端点上。
        MetConfiguration configuration = this.secureIncoming(2, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 直接控制 TLS 握手，避免 HTTP 客户端自动重试把身份拒绝掩盖为超时。
            SSLContext client = ConnectTlsUtils.clientContext("trust", null);
            int port = server.getListenAddress("connect").getPort();
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);

                // TLS 1.3 的拒绝可能晚于本地握手返回；不能收到任何 HTTP/2 应用字节。
                boolean rejected;
                try {
                    socket.startHandshake();
                    rejected = socket.getInputStream().read() < 0;
                } catch (IOException failure) {
                    boolean timedOut = failure instanceof SocketTimeoutException;
                    assertFalse("TLS rejection must not time out: " + failure, timedOut);
                    rejected = true;
                }
                assertTrue("Anonymous client must not receive HTTP/2 application data", rejected);
            }
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsUntrustedClientCertificate() throws Exception {
        // 客户端携带身份，但其签发者不属于服务端信任集。
        MetConfiguration configuration = this.secureIncoming(1, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 合法 Connect 请求必须以证书错误结束，不能以业务超时代表认证失败。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(ConnectTlsUtils.clientContext("trust", "untrusted-client")) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https", "echo"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另一 CA，Connect 请求不能绕过服务端证书校验。
        MetConfiguration configuration = this.secureIncoming(1, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 独立客户端直接观察握手异常，避免将网络拒绝混同于 RPC 超时。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(ConnectTlsUtils.clientContext("untrusted-trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https", "echo"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsWrongServerHostname() throws Exception {
        // 证书由受信 CA 签发，但它不属于当前访问地址，HTTPS 必须拒绝该身份。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        ConnectTlsUtils.serverTls(listener, 1, "NONE");
        listener.option("tlsKeyStore", "classpath:/tls/acceptance/wrong-host-server.p12");
        listener.protocol("connect").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 不关闭主机名校验，调用应以证书名称错误结束。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(ConnectTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https", "echo"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void incompatibleAlpnFailsBeforeRpcTimeout() throws Exception {
        // 服务端只支持 HTTP/1.1，客户端只接受 h2，协议协商应及时失败。
        MetConfiguration incoming = this.secureIncoming(1, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null);
        outgoing.setDefaultTimeout(10000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // Caller 收到传输错误，不能等到十秒调用期限才结束。
            MetClient remote = client.getRsfClient(server.getListenAddress("connect"));
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("mismatch", 7) };
            MetFuture pending = remote.asyncInvoke(service, "echo", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void http1RejectsBidirectionalStreaming() throws Exception {
        // 连接允许 HTTP/1，但双向 RPC 需要 HTTP/2 的独立收发方向。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 原始 Connect 流封装包含一条消息；对端明确固定 HTTP/1.1。
            byte[] message = "{\"text\":\"bidi\",\"number\":7}".getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(5 + message.length);
            frame.put((byte) 0).putInt(message.length).put(message);
            byte[] body = frame.array();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = HttpRequest.newBuilder(this.uri(server, "http", "chat")) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/connect+json") //
                    .header("Connect-Protocol-Version", "1") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 返回明确协议错误，避免把不支持的调用挂到业务超时。
            assertEquals(200, response.statusCode());
            ByteBuffer frames = ByteBuffer.wrap(response.body());
            assertEquals(2, frames.get() & 0xff);
            byte[] endStream = new byte[frames.getInt()];
            frames.get(endStream);
            JsonNode terminal = new ObjectMapper().readTree(endStream);
            assertEquals("unimplemented", terminal.path("error").path("code").asText());
            assertFalse(frames.hasRemaining());
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("connect").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration outgoing(int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol("connect").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration secureIncoming(int httpVersion, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        ConnectTlsUtils.serverTls(listener, httpVersion, clientAuth);
        listener.protocol("connect").addressScheme("https").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration secureOutgoing(int httpVersion, String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        ConnectorSettings sender = configuration.connector("http").option("httpVersion", httpVersion);
        ConnectTlsUtils.clientTls(sender, httpVersion, identity);
        sender.protocol("connect").addressScheme("https").contextPath("/rpc");
        return configuration;
    }

    private URI uri(MetContext context, String scheme, String method) {
        int port = context.getListenAddress("connect").getPort();
        String path = "/rpc/meteor.connect.demo.ConnectDemoService/" + method;
        return URI.create(scheme + "://127.0.0.1:" + port + path);
    }

    private HttpRequest unaryRequest(URI uri, String text) {
        String json = "{\"text\":\"" + text + "\",\"number\":7}";
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json") //
                .header("Connect-Protocol-Version", "1") //
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }
}
