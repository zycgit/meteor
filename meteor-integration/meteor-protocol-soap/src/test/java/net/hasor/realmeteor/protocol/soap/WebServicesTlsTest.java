/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.SoapResponseUtils;
import net.hasor.realmeteor.protocol.soap.support.SoapTlsSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesTlsTest {
    @Test(timeout = 20000)
    public void soap11UsesHttpsHttp1() throws Exception {
        // 启动 SOAP 1.1 单向 TLS 服务并发布普通 Java 接口。
        MetConfiguration configuration = this.server("1.1", HttpClient.Version.HTTP_1_1, false);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部 HTTPS 客户端必须通过 HTTP/1.1 完成调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "soap11-tls-h1");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap11-tls-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap11UsesHttpsHttp2Alpn() throws Exception {
        // 启动 SOAP 1.1 单向 TLS 服务并启用 h2 ALPN。
        MetConfiguration configuration = this.server("1.1", HttpClient.Version.HTTP_2, false);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部 HTTPS 客户端必须协商 HTTP/2 后完成调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "soap11-tls-h2");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap11-tls-h2", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap12UsesHttpsHttp1() throws Exception {
        // 启动 SOAP 1.2 单向 TLS 服务并发布普通 Java 接口。
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_1_1, false);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部 HTTPS 客户端必须通过 HTTP/1.1 完成调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "soap12-tls-h1");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap12-tls-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap12UsesHttpsHttp2Alpn() throws Exception {
        // 启动 SOAP 1.2 单向 TLS 服务并启用 h2 ALPN。
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_2, false);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部 HTTPS 客户端必须协商 HTTP/2 后完成调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "soap12-tls-h2");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap12-tls-h2", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap11UsesMutualTlsHttp1() throws Exception {
        // 启动要求客户端证书的 SOAP 1.1 HTTP/1.1 服务。
        MetConfiguration configuration = this.server("1.1", HttpClient.Version.HTTP_1_1, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 客户端携带受信任身份完成双向 TLS 调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "client", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "soap11-mtls-h1");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap11-mtls-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap11UsesMutualTlsHttp2Alpn() throws Exception {
        // 启动要求客户端证书的 SOAP 1.1 HTTP/2 服务。
        MetConfiguration configuration = this.server("1.1", HttpClient.Version.HTTP_2, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 客户端携带受信任身份并通过 ALPN 协商 HTTP/2。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "client", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "soap11-mtls-h2");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap11-mtls-h2", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap12UsesMutualTlsHttp1() throws Exception {
        // 启动要求客户端证书的 SOAP 1.2 HTTP/1.1 服务。
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_1_1, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 客户端携带受信任身份完成双向 TLS 调用。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "client", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "soap12-mtls-h1");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap12-mtls-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void soap12UsesMutualTlsHttp2Alpn() throws Exception {
        // 启动要求客户端证书的 SOAP 1.2 HTTP/2 服务。
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_2, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 客户端携带受信任身份并通过 ALPN 协商 HTTP/2。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "client", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "soap12-mtls-h2");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap12-mtls-h2", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsMissingClientCertificate() throws Exception {
        // 服务端要求客户端身份，业务实现记录是否越过了 TLS 边界。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.server("1.1", HttpClient.Version.HTTP_1_1, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishCountingEcho(context, calls);

            // 客户端信任服务器但不提供身份，握手必须主动失败。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "missing-client");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Certificate rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsUntrustedClientCertificate() throws Exception {
        // SOAP 1.2 HTTP/2 服务端只信任验收 CA 签发的客户端身份。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_2, true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishCountingEcho(context, calls);

            // 客户端信任服务器，但携带另一 CA 签发的身份。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "untrusted-client", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "untrusted-client");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Certificate rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 20000)
    public void httpsRejectsWrongHostCertificate() throws Exception {
        // 服务端证书由受信任 CA 签发，但 SAN 不包含 localhost。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        MetConfiguration.ConnectorSettings connector = SoapTlsSupport.server(configuration, "http/1.1", false);
        connector.option("tlsKeyStore", "classpath:/tls/acceptance/wrong-host-server.p12") //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", "1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishCountingEcho(context, calls);

            // 默认 HTTPS 主机名校验必须拒绝证书，不能只验证签发 CA。
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "", "trust");
            HttpRequest request = this.request(this.endpoint(context), "1.1", "wrong-host");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Hostname rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 20000)
    public void httpsRejectsUntrustedServerCertificate() throws Exception {
        // SOAP 1.2 HTTP/2 服务使用正常服务端身份，客户端改用另一套信任库。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.server("1.2", HttpClient.Version.HTTP_2, false);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishCountingEcho(context, calls);

            // 客户端不能接受信任库之外的服务器证书。
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "", "untrusted-trust");
            HttpRequest request = this.request(this.endpoint(context), "1.2", "untrusted-server");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Trust rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    private MetConfiguration server(String soapVersion, HttpClient.Version httpVersion, boolean mutual) {
        String alpn = httpVersion == HttpClient.Version.HTTP_2 ? "h2" : "http/1.1";
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        SoapTlsSupport.server(configuration, alpn, mutual) //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", soapVersion);
        return configuration;
    }

    private HttpClient http(HttpClient.Version version, String identity, String trustStore) throws Exception {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .sslContext(SoapTlsSupport.sslContext(identity, trustStore)) //
                .version(version) //
                .build();
    }

    private HttpRequest request(URI endpoint, String version, String value) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint) //
                .timeout(Duration.ofSeconds(15));
        if (StringUtils.equals("1.2", version)) {
            String action = "urn:hasor:meteor:soap:EchoService/echo";
            request.header("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        } else {
            request.header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"");
        }
        return request.POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope(version, value))) //
                .build();
    }

    private byte[] echoEnvelope(String version, String message) {
        String namespace = StringUtils.equals("1.2", version) //
                ? "http://www.w3.org/2003/05/soap-envelope" //
                : "http://schemas.xmlsoap.org/soap/envelope/";
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="%s">
                    <soap:Body>
                        <tns:echo xmlns:tns="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </tns:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(namespace, message);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private void publishCountingEcho(MetContext context, AtomicInteger calls) throws Exception {
        EchoService service = value -> {
            calls.incrementAndGet();
            return value;
        };
        context.publisher() //
                .rsfService(EchoService.class, service) //
                .aliasName("soap", "EchoService") //
                .protocol("soap") //
                .register();
    }

    private URI endpoint(MetContext context) {
        int port = context.getListenAddress("soap").getPort();
        return URI.create("https://localhost:" + port + "/services/EchoService");
    }
}
