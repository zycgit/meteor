/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
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
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.Test;
import static org.junit.Assert.*;

/** XML-RPC 在 HTTP/2、TLS 和客户端证书认证下仍通过公开入口执行真实业务。 */
public class XmlRpcTlsTest {
    @Test(timeout = 20000)
    public void httpsHttp1UsesServerAuthentication() throws Exception {
        // 两端显式配置 HTTP/1 与测试 CA，传输参数通过正式 bootstrap 入口生效。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        XmlRpcTlsUtils.clientTls(sender, 1, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再发布和订阅普通 Java 接口，客户端没有本地服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 通过动态监听地址完成真正的加密网络调用。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp2UsesAlpn() throws Exception {
        // 两端显式配置 HTTP/2 与测试 CA，传输参数通过正式 bootstrap 入口生效。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 2, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        XmlRpcTlsUtils.clientTls(sender, 2, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再发布和订阅普通 Java 接口，客户端没有本地服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 通过动态监听地址完成真正的加密网络调用。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp1UsesMutualAuthentication() throws Exception {
        // 两端显式配置 HTTP/1 与测试 CA，传输参数通过正式 bootstrap 入口生效。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "REQUIRE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        XmlRpcTlsUtils.clientTls(sender, 1, "client");
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再发布和订阅普通 Java 接口，客户端没有本地服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 通过动态监听地址完成真正的加密网络调用。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp2UsesMutualAuthentication() throws Exception {
        // 两端显式配置 HTTP/2 与测试 CA，传输参数通过正式 bootstrap 入口生效。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 2, "REQUIRE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        XmlRpcTlsUtils.clientTls(sender, 2, "client");
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再发布和订阅普通 Java 接口，客户端没有本地服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 通过动态监听地址完成真正的加密网络调用。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsPreservesBase64BinaryValues() throws Exception {
        // XML-RPC 的 base64 数据通过 HTTPS 传输，不经文本转换丢失字节。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        XmlRpcTlsUtils.clientTls(sender, 1, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅同一份用户接口。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 二进制中的零值和高位字节必须在远程返回后保持一致。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            byte[] content = { 0, 1, 64, 127, (byte) 128, (byte) 255 };
            assertArrayEquals(content, remote.binary(content));
        }
    }

    @Test(timeout = 20000)
    public void httpsPreservesBusinessFault() throws Exception {
        // HTTPS 上的 HTTP/2 响应仍由 XML-RPC fault 携带业务错误。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 2, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        XmlRpcTlsUtils.clientTls(sender, 2, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布普通服务，业务方法自行抛出带有错误码的异常。
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 客户端收到相同错误码及描述，不能将 fault 当作 HTTP 或 TLS 错误。
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "业务拒绝" };
            MetFuture call = remote.asyncInvoke(service, "fail", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof XmlRpcException);
            XmlRpcException fault = (XmlRpcException) failure.getCause();
            assertEquals(42, fault.getCode());
            assertEquals("业务拒绝", fault.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp2WithAlpn() throws Exception {
        // 服务端只宣告 h2，独立客户端必须通过 TLS ALPN 选择 HTTP/2。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 2, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();

            // 不绕过证书校验，实际响应版本证明不是 HTTP/1.1 回退。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(XmlRpcTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_2).build();
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String body = "<methodCall><methodName>demo.echo</methodName>" //
                    + "<params><param><value><string>alpn</string></value></param></params></methodCall>";
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertTrue(response.body(), response.body().contains("<string>alpn</string>"));
        }
    }

    @Test(timeout = 20000)
    public void http1MutualTlsRejectsMissingClientCertificate() throws Exception {
        // 使用不同身份或信任配置触发真实 TLS 拒绝，不通过业务超时模拟握手失败。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "REQUIRE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();

            // 独立客户端发送正常 XML-RPC 请求，证书问题应在业务调用之前被拒绝。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(XmlRpcTlsUtils.clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String body = "<methodCall><methodName>demo.echo</methodName>" //
                    + "<params><param><value><string>rejected</string></value></param></params></methodCall>";
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            // 身份校验必须在派发前失败，错误不能是请求等待超时。
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void http2MutualTlsRejectsMissingClientCertificate() throws Exception {
        // 只允许 h2 的监听器要求客户端身份，服务从公开 bootstrap 入口发布。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 2, "REQUIRE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();

            // 直接控制 TLS 握手，避免 HTTP 客户端的重试和请求计时混入证书拒绝判定。
            SSLContext client = XmlRpcTlsUtils.clientContext("trust", null);
            int port = server.getListenAddress("xmlrpc").getPort();
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);

                // TLS 1.3 的拒绝可能在本地握手返回后到达；任何应用数据都表示未正确隔离。
                boolean rejected;
                try {
                    socket.startHandshake();
                    rejected = socket.getInputStream().read() < 0;
                } catch (IOException failure) {
                    boolean timedOut = failure instanceof SocketTimeoutException;
                    assertFalse("TLS rejection must not time out: " + failure, timedOut);
                    rejected = true;
                }
                assertTrue("An anonymous peer must not receive HTTP/2 application data", rejected);
            }
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsUntrustedClientCertificate() throws Exception {
        // 使用不同身份或信任配置触发真实 TLS 拒绝，不通过业务超时模拟握手失败。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "REQUIRE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();

            // 独立客户端发送正常 XML-RPC 请求，证书问题应在业务调用之前被拒绝。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(XmlRpcTlsUtils.clientContext("trust", "untrusted-client")) //
                    .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String body = "<methodCall><methodName>demo.echo</methodName>" //
                    + "<params><param><value><string>rejected</string></value></param></params></methodCall>";
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            // 身份校验必须在派发前失败，错误不能是请求等待超时。
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsUntrustedServerCertificate() throws Exception {
        // 使用不同身份或信任配置触发真实 TLS 拒绝，不通过业务超时模拟握手失败。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();

            // 独立客户端发送正常 XML-RPC 请求，证书问题应在业务调用之前被拒绝。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(XmlRpcTlsUtils.clientContext("untrusted-trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String body = "<methodCall><methodName>demo.echo</methodName>" //
                    + "<params><param><value><string>rejected</string></value></param></params></methodCall>";
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            // 身份校验必须在派发前失败，错误不能是请求等待超时。
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void incompatibleAlpnFailsBeforeRpcTimeout() throws Exception {
        // 服务端仅支持 HTTP/1.1，客户端仅请求 h2；双方没有可用的 ALPN 交集。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(10000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        XmlRpcTlsUtils.clientTls(sender, 2, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher().rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").register();

            // 公共 Caller 应收到握手错误，不应等到十秒业务期限。
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "mismatch" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
        }
    }
}
