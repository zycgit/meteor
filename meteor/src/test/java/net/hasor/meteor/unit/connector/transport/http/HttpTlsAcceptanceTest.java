/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolTestAccess;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.unit.connector.transport.http.HttpTlsObservationPipeline.Observation;
import org.junit.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.http.HttpConnectorTestAccess.newConnector;
import static net.hasor.meteor.support.connector.transport.http.HttpTransportPlans.routes;
import static org.junit.Assert.*;

/** 验证 HTTP 明文路由以及生产 TLS、ALPN 和客户端身份校验边界。 */
public class HttpTlsAcceptanceTest {
    private static final String PASSWORD               = "meteor-test";
    private static final String SERVER_STORE           = "classpath:/tls/acceptance/server.p12";
    private static final String CLIENT_STORE           = "classpath:/tls/acceptance/client.p12";
    private static final String UNTRUSTED_CLIENT_STORE = "classpath:/tls/acceptance/untrusted-client.p12";
    private static final String WRONG_HOST_STORE       = "classpath:/tls/acceptance/wrong-host-server.p12";
    private static final String TRUST_STORE            = "classpath:/tls/acceptance/trust.p12";
    private static final String UNTRUSTED_TRUST_STORE  = "classpath:/tls/acceptance/untrusted-trust.p12";

    @Test(timeout = 15000)
    public void cleartextListenerAcceptsHttp1AndH2PriorKnowledge() throws Exception {
        // 同一个明文监听器分别接收 HTTP/1.1 和 HTTP/2 prior-knowledge 连接。
        ConnectorConfig serverConfig = this.config(true, 1, Map.of());
        ConnectorConfig http1Config = this.config(false, 1, Map.of());
        ConnectorConfig http2Config = this.config(false, 2, Map.of());
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> http1 = this.connector(http1Config); //
             NetworkConnector<HttpExchange> http2 = this.connector(http2Config); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), this::echo)) {
            NetworkChannel<HttpExchange> http1Channel = this.connect(http1, http1Config, listen.getAddress());
            NetworkChannel<HttpExchange> http2Channel = this.connect(http2, http2Config, listen.getAddress());

            // 两个请求的线上版本由服务端回显，证明路由选择来自实际连接。
            HttpResponse http1Response = this.invoke(http1Channel, 1, "/clear-http1");
            HttpResponse http2Response = this.invoke(http2Channel, 2, "/clear-http2");
            assertArrayEquals(bytes("1:/clear-http1"), http1Response.body());
            assertArrayEquals(bytes("2:/clear-http2"), http2Response.body());
        }
    }

    @Test(timeout = 15000)
    public void oneWayTlsAllowsHttp1WithoutAlpn() throws Exception {
        // 服务端提供身份，客户端只信任服务端；两端都不声明 ALPN。
        String serverProbe = "one-way-server";
        String clientProbe = "one-way-client";
        HttpTlsObservationPipeline.reset(serverProbe);
        HttpTlsObservationPipeline.reset(clientProbe);
        ConnectorConfig serverConfig = this.config(true, 1, this.serverTls(null, serverProbe));
        ConnectorConfig clientConfig = this.config(false, 1, this.clientTls(null, TRUST_STORE, clientProbe));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), this::echo)) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 缺少 ALPN 时 HTTPS 兼容 HTTP/1.1，实际请求仍通过 TLS 到达。
            HttpResponse response = this.invoke(channel, 1, "/no-alpn");
            assertArrayEquals(bytes("1:/no-alpn"), response.body());
            Observation serverSession = HttpTlsObservationPipeline.await(serverProbe);
            Observation clientSession = HttpTlsObservationPipeline.await(clientProbe);
            assertSession(serverSession, null);
            assertSession(clientSession, null);
            assertNull(serverSession.peerSubject());
            assertNull(clientSession.localSubject());
            assertEquals("CN=localhost", clientSession.peerSubject());
        }
    }

    @Test(timeout = 15000)
    public void sameTlsListenerSelectsH2AndHttp1ByAlpn() throws Exception {
        // 一个监听器声明两种 ALPN，各客户端只声明自己实际使用的 HTTP 版本。
        String serverProbe = "alpn-server";
        String h2Probe = "alpn-h2-client";
        String http1Probe = "alpn-http1-client";
        HttpTlsObservationPipeline.reset(serverProbe);
        HttpTlsObservationPipeline.reset(h2Probe);
        HttpTlsObservationPipeline.reset(http1Probe);
        ConnectorConfig serverConfig = this.config(true, 1, this.serverTls("h2,http/1.1", serverProbe));
        ConnectorConfig h2Config = this.config(false, 2, this.clientTls("h2", TRUST_STORE, h2Probe));
        ConnectorConfig http1Config = this.config(false, 1, this.clientTls("http/1.1", TRUST_STORE, http1Probe));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> h2Client = this.connector(h2Config); //
             NetworkConnector<HttpExchange> http1Client = this.connector(http1Config); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), this::echo)) {
            NetworkChannel<HttpExchange> h2 = this.connect(h2Client, h2Config, listen.getAddress());
            NetworkChannel<HttpExchange> http1 = this.connect(http1Client, http1Config, listen.getAddress());

            // 请求版本和两端握手结果必须一致，不能根据客户端配置推测协议。
            assertArrayEquals(bytes("2:/alpn-h2"), this.invoke(h2, 2, "/alpn-h2").body());
            assertArrayEquals(bytes("1:/alpn-http1"), this.invoke(http1, 1, "/alpn-http1").body());
            assertSession(HttpTlsObservationPipeline.await(h2Probe), "h2");
            assertSession(HttpTlsObservationPipeline.await(http1Probe), "http/1.1");
            Observation firstServerSession = HttpTlsObservationPipeline.await(serverProbe);
            Observation secondServerSession = HttpTlsObservationPipeline.await(serverProbe);
            assertNotNull(firstServerSession);
            assertNotNull(secondServerSession);
            List<String> selected = List.of(firstServerSession.applicationProtocol(), //
                    secondServerSession.applicationProtocol());
            assertTrue(selected.contains("h2"));
            assertTrue(selected.contains("http/1.1"));
        }
    }

    @Test(timeout = 15000)
    public void mutualTlsUsesDistinctServerAndClientIdentities() throws Exception {
        // 服务端要求客户端证书，两端使用同一 CA 签发的不同身份。
        String serverProbe = "mtls-server";
        String clientProbe = "mtls-client";
        HttpTlsObservationPipeline.reset(serverProbe);
        HttpTlsObservationPipeline.reset(clientProbe);
        Map<String, String> serverOptions = this.serverTls("h2", serverProbe);
        serverOptions.put("tlsTrustStore", TRUST_STORE);
        serverOptions.put("tlsTrustStorePassword", PASSWORD);
        serverOptions.put("tlsClientAuth", "REQUIRE");
        Map<String, String> clientOptions = this.clientTls("h2", TRUST_STORE, clientProbe);
        clientOptions.put("tlsKeyStore", CLIENT_STORE);
        clientOptions.put("tlsKeyStorePassword", PASSWORD);
        ConnectorConfig serverConfig = this.config(true, 2, serverOptions);
        ConnectorConfig clientConfig = this.config(false, 2, clientOptions);
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), this::echo)) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 完成真实请求后，从已协商会话读取双方实际使用的证书身份。
            HttpResponse response = this.invoke(channel, 2, "/mtls");
            assertArrayEquals(bytes("2:/mtls"), response.body());
            Observation serverSession = HttpTlsObservationPipeline.await(serverProbe);
            Observation clientSession = HttpTlsObservationPipeline.await(clientProbe);
            assertSession(serverSession, "h2");
            assertSession(clientSession, "h2");
            assertEquals("CN=localhost", serverSession.localSubject());
            assertEquals("CN=meteor-test-client", serverSession.peerSubject());
            assertEquals("CN=meteor-test-client", clientSession.localSubject());
            assertEquals("CN=localhost", clientSession.peerSubject());
        }
    }

    @Test(timeout = 15000)
    public void mutualTlsRejectsClientWithoutCertificate() throws Exception {
        // 服务端信任测试 CA 并强制客户端认证，客户端只配置服务端信任。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        Map<String, String> serverOptions = this.serverTls("h2", "missing-client-server");
        serverOptions.put("tlsTrustStore", TRUST_STORE);
        serverOptions.put("tlsTrustStorePassword", PASSWORD);
        serverOptions.put("tlsClientAuth", "REQUIRE");
        ConnectorConfig serverConfig = this.config(true, 2, serverOptions);
        ConnectorConfig clientConfig = this.config(false, 2, this.clientTls("h2", TRUST_STORE, "missing-client"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 身份缺失必须以 TLS 握手异常结束，业务请求不能进入 HTTP 接收器。
            ExecutionException failure = this.invokeFailure(channel, 2, "/missing-client");
            assertHandshakeFailure(failure);
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void mutualTlsRejectsClientFromUntrustedCa() throws Exception {
        // 客户端证书由另一 CA 签发，服务端仍只信任主测试 CA。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        Map<String, String> serverOptions = this.serverTls("h2", "bad-client-server");
        serverOptions.put("tlsTrustStore", TRUST_STORE);
        serverOptions.put("tlsTrustStorePassword", PASSWORD);
        serverOptions.put("tlsClientAuth", "REQUIRE");
        Map<String, String> clientOptions = this.clientTls("h2", TRUST_STORE, "bad-client");
        clientOptions.put("tlsKeyStore", UNTRUSTED_CLIENT_STORE);
        clientOptions.put("tlsKeyStorePassword", PASSWORD);
        ConnectorConfig serverConfig = this.config(true, 2, serverOptions);
        ConnectorConfig clientConfig = this.config(false, 2, clientOptions);
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 非受信身份在握手阶段失败，不能退化成匿名客户端。
            ExecutionException failure = this.invokeFailure(channel, 2, "/bad-client");
            assertHandshakeFailure(failure);
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void clientRejectsServerOutsideItsTrustStore() throws Exception {
        // 服务端使用主测试 CA，客户端故意只信任另一套测试 CA。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = this.config(true, 2, this.serverTls("h2", "untrusted-server"));
        ConnectorConfig clientConfig = this.config(false, 2, //
                this.clientTls("h2", UNTRUSTED_TRUST_STORE, "bad-trust"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 客户端必须报告证书路径握手异常，不能发送未经认证的 HTTP 请求。
            ExecutionException failure = this.invokeFailure(channel, 2, "/untrusted-server");
            assertHandshakeFailure(failure);
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void clientRejectsServerWithWrongHostname() throws Exception {
        // 服务端证书受信任，但 SAN 只包含 other.invalid。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        Map<String, String> serverOptions = this.serverTls("h2", "wrong-host-server");
        serverOptions.put("tlsKeyStore", WRONG_HOST_STORE);
        ConnectorConfig serverConfig = this.config(true, 2, serverOptions);
        ConnectorConfig clientConfig = this.config(false, 2, this.clientTls("h2", TRUST_STORE, "wrong-host"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 信任链成功不能替代目标主机验证，错误 SAN 仍在握手阶段失败。
            ExecutionException failure = this.invokeFailure(channel, 2, "/wrong-host");
            assertHandshakeFailure(failure);
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void tlsHandshakeFailsWhenAlpnHasNoCommonProtocol() throws Exception {
        // 服务端只允许 HTTP/1.1，客户端只允许 h2，两端没有 ALPN 交集。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = this.config(true, 1, this.serverTls("http/1.1", "alpn-mismatch-server"));
        ConnectorConfig clientConfig = this.config(false, 2, this.clientTls("h2", TRUST_STORE, "alpn-mismatch-client"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // ALPN 失败属于握手失败，不能等待请求超时后才暴露。
            ExecutionException failure = this.invokeFailure(channel, 2, "/alpn-mismatch");
            assertHandshakeFailure(failure);
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void http2ClientDoesNotDowngradeAfterHttp1AlpnSelection() throws Exception {
        // 客户端允许协商两种协议但调用通道明确要求 HTTP/2，服务端只选择 HTTP/1.1。
        String clientProbe = "no-downgrade-client";
        HttpTlsObservationPipeline.reset(clientProbe);
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = this.config(true, 1, this.serverTls("http/1.1", "no-downgrade-server"));
        Map<String, String> clientOptions = this.clientTls("h2,http/1.1", TRUST_STORE, clientProbe);
        ConnectorConfig clientConfig = this.config(false, 2, clientOptions);
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 握手确实选择 HTTP/1.1，HTTP/2 通道随后明确拒绝而不是发送降级请求。
            ExecutionException failure = this.invokeFailure(channel, 2, "/no-downgrade");
            assertCauseMessage(failure, "HTTP/2 requires ALPN h2");
            assertSession(HttpTlsObservationPipeline.await(clientProbe), "http/1.1");
            assertTrue(received.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void tlsHttp1RequestCannotUpgradeToH2c() throws Exception {
        // TLS 监听通过 ALPN 选择 HTTP/1.1，客户端随后发送一份语法有效的 h2c Upgrade 请求。
        ConnectorConfig serverConfig = this.config(true, 1, this.serverTls("http/1.1", "tls-upgrade-server"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), this::echo); //
             SSLSocket socket = this.sslSocket(listen.getAddress().getPort())) {
            socket.startHandshake();
            assertEquals("http/1.1", socket.getApplicationProtocol());

            // HTTPS 内的 Upgrade 只是一组普通 HTTP/1.1 头部，不能切换到明文 h2c 分支。
            String request = "GET /tls-upgrade HTTP/1.1\r\n" + //
                    "Host: localhost\r\n" + //
                    "Connection: Upgrade, HTTP2-Settings, close\r\n" + //
                    "Upgrade: h2c\r\n" + //
                    "HTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // 响应保持 HTTP/1.1 200，服务端回显的请求版本也仍为 1。
            InputStream input = socket.getInputStream();
            assertEquals("HTTP/1.1 200 OK", readLine(input));
            int length = contentLength(input);
            assertArrayEquals(bytes("1:/tls-upgrade"), input.readNBytes(length));
        }
    }

    @Test(timeout = 15000)
    public void httpsSchemeWithoutTlsFailsBeforeSendingPlaintext() throws Exception {
        // 服务端保持明文，客户端只声明 https scheme，没有启用 TLS 或安装自定义 TLS pipeline。
        LinkedBlockingQueue<HttpExchange> received = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = this.config(true, 1, Map.of());
        ConnectorConfig clientConfig = this.config(false, 1, Map.of("httpScheme", "https"));
        try (NetworkConnector<HttpExchange> server = this.connector(serverConfig); //
             NetworkConnector<HttpExchange> client = this.connector(clientConfig); //
             NetworkListen listen = server.bind(serverConfig.address(), routes(serverConfig), //
                     channel -> this.echo(channel, received))) {
            NetworkChannel<HttpExchange> channel = this.connect(client, clientConfig, listen.getAddress());

            // 安全声明与真实传输不一致时立即失败，服务端不能收到明文 HTTP 请求。
            ExecutionException failure = this.invokeFailure(channel, 1, "/plaintext");
            assertCauseMessage(failure, "HTTPS requires a TLS transport pipeline");
            assertTrue(received.isEmpty());
        }
    }

    //

    private NetworkConnector<HttpExchange> connector(ConnectorConfig config) {
        return newConnector(transport(config), this.getClass().getClassLoader());
    }

    private ConnectorConfig config(boolean bind, int version, Map<String, String> options) {
        Map<String, String> values = new HashMap<>(options);
        values.put("httpVersion", String.valueOf(version));
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolTestAccess.config("http", "http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", address, values, List.of(protocol), bind);
    }

    private Map<String, String> serverTls(String alpn, String observation) {
        Map<String, String> options = this.tls(alpn, observation);
        options.put("tlsKeyStore", SERVER_STORE);
        options.put("tlsKeyStorePassword", PASSWORD);
        return options;
    }

    private Map<String, String> clientTls(String alpn, String trustStore, String observation) {
        Map<String, String> options = this.tls(alpn, observation);
        options.put("tlsTrustStore", trustStore);
        options.put("tlsTrustStorePassword", PASSWORD);
        return options;
    }

    private Map<String, String> tls(String alpn, String observation) {
        Map<String, String> options = new HashMap<>();
        options.put("tlsEnabled", "true");
        options.put("transportPipeline", HttpTlsObservationPipeline.class.getName());
        options.put("tlsObservation", observation);
        if (alpn != null) {
            options.put("tlsAlpn", alpn);
        }
        return options;
    }

    private NetworkChannel<HttpExchange> connect(NetworkConnector<HttpExchange> connector, //
            ConnectorConfig config, InterAddress target) throws Exception {
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        connector.connect(target, routes(config), null, channel -> new ChannelListener<HttpExchange>() {
            @Override
            public void connected() {
                connected.completed(channel);
            }

            @Override
            public void receive(HttpExchange message) {
            }

            @Override
            public void closed(Throwable cause) {
                connected.failed(cause);
            }
        }).get(3, TimeUnit.SECONDS);
        return connected.get(3, TimeUnit.SECONDS);
    }

    private ChannelListener<HttpExchange> echo(NetworkChannel<HttpExchange> channel) {
        return this.echo(channel, null);
    }

    private ChannelListener<HttpExchange> echo(NetworkChannel<HttpExchange> channel, //
            LinkedBlockingQueue<HttpExchange> received) {
        return new ChannelListener<HttpExchange>() {
            @Override
            public void connected() {
            }

            @Override
            public void receive(HttpExchange exchange) {
                if (received != null) {
                    received.add(exchange);
                }
                String result = exchange.request().version() + ":" + exchange.request().uri();
                exchange.respond(HttpResponse.newBuffered(200, Map.of(), bytes(result), Map.of()));
                channel.write(exchange);
            }

            @Override
            public void closed(Throwable cause) {
            }
        };
    }

    private HttpResponse invoke(NetworkChannel<HttpExchange> channel, int version, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], version, Map.of());
        HttpExchange exchange = new HttpExchange(request);
        channel.write(exchange).get(3, TimeUnit.SECONDS);
        return exchange.response().get(3, TimeUnit.SECONDS);
    }

    private ExecutionException invokeFailure(NetworkChannel<HttpExchange> channel, int version, String path) {
        HttpRequest request = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], version, Map.of());
        HttpExchange exchange = new HttpExchange(request);
        channel.write(exchange);
        return assertThrows(ExecutionException.class, () -> {
            exchange.response().get(4, TimeUnit.SECONDS);
        });
    }

    private SSLSocket sslSocket(int port) throws Exception {
        SSLContext context = this.clientSslContext();
        Socket raw = new Socket("127.0.0.1", port);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, "localhost", port, true);
        socket.setSoTimeout(3000);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setApplicationProtocols(new String[] { "http/1.1" });
        socket.setSSLParameters(parameters);
        return socket;
    }

    private SSLContext clientSslContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = this.getClass().getResourceAsStream("/tls/acceptance/trust.p12")) {
            assertNotNull(input);
            trustStore.load(input, PASSWORD.toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static void assertSession(Observation observation, String applicationProtocol) {
        assertNotNull(observation);
        assertTrue(observation.tlsProtocol().startsWith("TLSv1."));
        assertEquals(applicationProtocol, observation.applicationProtocol());
    }

    private static void assertHandshakeFailure(ExecutionException failure) {
        Throwable current = failure;
        SSLException selected = null;
        while (current != null) {
            if (current instanceof SSLHandshakeException handshake) {
                selected = handshake;
                break;
            }
            if (selected == null && current instanceof SSLException ssl) {
                selected = ssl;
            }
            current = current.getCause();
        }
        assertNotNull(describe(failure), selected);
    }

    private static void assertCauseMessage(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(text)) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError(describe(failure));
    }

    private static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (result.length() > 0) {
                result.append(" -> ");
            }
            result.append(current.getClass().getName()).append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return result.toString();
    }

    private static int contentLength(InputStream input) throws Exception {
        int length = -1;
        String line;
        while (!StringUtils.equals("", line = readLine(input))) {
            int separator = line.indexOf(':');
            if (separator > 0 && StringUtils.equalsIgnoreCase("Content-Length", line.substring(0, separator))) {
                length = Integer.parseInt(line.substring(separator + 1).trim());
            }
        }
        assertFalse("Missing Content-Length", length < 0);
        return length;
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
