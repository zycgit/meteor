/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianInputFactory;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Hessian 的 HTTP 版本、安全握手及证书校验从公开发布入口验证。 */
public class HessianSecureScenarioTest {
    @Test(timeout = 20000)
    public void hessianOneCallsOverPlainHttp1() throws Exception {
        // Hessian 1 调用使用普通 HTTP/1 链路。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, 1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void hessianTwoCallsOverPlainHttp1() throws Exception {
        // Hessian 2 调用使用普通 HTTP/1 链路。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void hessianTwoCallsOverHttp2PriorKnowledge() throws Exception {
        // 客户端显式选择 HTTP/2 prior knowledge，无需 TLS。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void hessianOneUsesHttpsHttp1ServerAuthentication() throws Exception {
        // Hessian 1 保持相同调用格式，仅由 HTTP 传输开启 TLS。
        MetConfiguration incoming = this.secureIncoming(1, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(1, 1, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void hessianTwoUsesHttpsHttp1MutualAuthentication() throws Exception {
        // HTTP/1 HTTPS 同时验证服务端和客户端证书。
        MetConfiguration incoming = this.secureIncoming(1, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(1, 2, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void hessianTwoUsesHttpsHttp2MutualAuthentication() throws Exception {
        // Hessian 2 经双向 TLS 与 h2 ALPN 传输。
        MetConfiguration incoming = this.secureIncoming(2, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, 2, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动两端后再发布、订阅服务，客户端没有可供本地直调的实现。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议适配根据服务别名选定路径，调用方只提供实际监听地址。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("Hessian 中文", remote.echo("Hessian 中文"));
        }
    }

    @Test(timeout = 20000)
    public void independentClientUpgradesHessianCallToH2c() throws Throwable {
        // 同一 HTTP 监听接收 h2c Upgrade，调用内容由 Caucho 官方编解码器生成。
        MetConfiguration configuration = this.incoming();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // JDK 的 HTTP/2 明文客户端先发送 Upgrade，响应版本证明升级成功。
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/rpc/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.call("h2c"))).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("h2c", this.reply(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp2WithAlpn() throws Throwable {
        // 服务端仅宣告 h2，独立客户端通过 TLS ALPN 选择 HTTP/2。
        MetConfiguration configuration = this.secureIncoming(2, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 独立 JDK 客户端严格验证测试 CA，用官方 Hessian 数据完成业务调用。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HessianTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_2).build();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.call("alpn"))).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("alpn", this.reply(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void http2MutualTlsRejectsMissingClientCertificate() throws Exception {
        // Hessian 挂载在要求客户端身份的 h2 HTTPS 端点上。
        MetConfiguration configuration = this.secureIncoming(2, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 控制 TLS 握手，避免 HTTP 客户端自动重试把证书拒绝掩盖为超时。
            SSLContext client = HessianTlsUtils.clientContext("trust", null);
            int port = server.getListenAddress("hessian").getPort();
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);

                // TLS 1.3 的拒绝可以晚于本地握手返回；任何应用字节都表示隔离失败。
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
        // 客户端有证书，但其签发者不属于端点信任集。
        MetConfiguration configuration = this.secureIncoming(1, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 真实 Hessian 调用必须在身份校验时失败，不能以业务超时代表拒绝。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HessianTlsUtils.clientContext("trust", "untrusted-client")) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.call("rejected"))).build();
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另外的 CA，合法 Hessian 消息不能绕过服务端证书校验。
        MetConfiguration configuration = this.secureIncoming(1, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 通过独立客户端观察握手异常，避免将网络拒绝等同于 RPC 超时。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HessianTlsUtils.clientContext("untrusted-trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.call("rejected"))).build();
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsWrongServerHostname() throws Exception {
        // 证书由受信 CA 签发，但名称不属于当前访问地址，HTTPS 必须拒绝它。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        HessianTlsUtils.serverTls(listener, 1, "NONE");
        listener.option("tlsKeyStore", "classpath:/tls/acceptance/wrong-host-server.p12");
        listener.protocol("hessian").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 不关闭主机名校验，业务调用须以证书身份错误结束。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HessianTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.call("rejected"))).build();
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void incompatibleAlpnFailsBeforeRpcTimeout() throws Exception {
        // 服务端只支持 HTTP/1.1，客户端只接受 h2，协议协商必须及时失败。
        MetConfiguration incoming = this.secureIncoming(1, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, 2, null);
        outgoing.setDefaultTimeout(10000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // Caller 应收到传输层错误，而不是等到十秒调用期限。
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "mismatch" };
            MetFuture pending = remote.asyncInvoke(service, "echo", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("hessian").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration outgoing(int httpVersion, int hessianVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.setDefaultTimeout(5000);
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol("hessian").contextPath("/rpc").option("version", hessianVersion);
        return configuration;
    }

    private MetConfiguration secureIncoming(int httpVersion, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        HessianTlsUtils.serverTls(listener, httpVersion, clientAuth);
        listener.protocol("hessian").addressScheme("https").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration secureOutgoing(int httpVersion, int hessianVersion, String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.setDefaultTimeout(5000);
        ConnectorSettings sender = configuration.connector("http").option("httpVersion", httpVersion);
        HessianTlsUtils.clientTls(sender, httpVersion, identity);
        sender.protocol("hessian").addressScheme("https").contextPath("/rpc").option("version", hessianVersion);
        return configuration;
    }

    private byte[] call(String value) throws IOException {
        // 独立对端使用官方 Hessian 2 writer，不引用插件的 Codec。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.call("echo", new Object[] { value });
        output.close();
        return bytes.toByteArray();
    }

    private Object reply(byte[] body) throws Throwable {
        // 先验证版本头，再由官方 reader 读取返回值与 fault。
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        HessianInputFactory.HeaderType header = new HessianInputFactory().readHeader(bytes);
        assertEquals(HessianInputFactory.HeaderType.HESSIAN_2, header);
        Hessian2Input input = new Hessian2Input(bytes);
        try {
            return input.readReply(String.class);
        } finally {
            input.close();
        }
    }
}
