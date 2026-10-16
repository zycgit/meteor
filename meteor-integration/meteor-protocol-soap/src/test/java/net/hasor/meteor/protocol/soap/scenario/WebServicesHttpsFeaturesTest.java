/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.protocol.soap.scenario.example.*;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.support.SignalService;
import net.hasor.meteor.protocol.soap.scenario.support.SoapTlsSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesHttpsFeaturesTest {
    private static final Pattern SCHEMA_LOCATION = Pattern.compile("schemaLocation=\"([^\"]+)\"");

    @Test(timeout = 20000)
    public void transportTlsDefinesHttpsWsdlEndpoint() throws Exception {
        // HTTPS h2 监听由端点 TLS 决定真实传输地址，不配置遗留 httpScheme。
        MetConfiguration configuration = this.server("1.1", "h2", false, "inline");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 通过真实 TLS/ALPN 读取 WSDL，动态地址和导入位置都保留 HTTPS。
            URI endpoint = this.endpoint(context, "/services/EchoService");
            HttpRequest request = this.get(URI.create(endpoint + "?wsdl"));
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "");
            HttpResponse<String> response = this.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/xml"));
            assertTrue(response.body(), response.body().contains("location=\"" + endpoint + "\""));
            assertTrue(response.body(), response.body().contains(endpoint + "?xsd=echo-types.xsd"));
        }
    }

    @Test(timeout = 20000)
    public void importedXsdIsFetchedOverRealHttpsHttp1() throws Exception {
        // HTTPS HTTP/1.1 监听发布 WSDL 绑定的外部 XSD。
        MetConfiguration configuration = this.server("1.1", "http/1.1", false, "inline");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 先读取在线 WSDL，再跟随它发布的 schemaLocation 导入真实 XSD。
            URI endpoint = this.endpoint(context, "/services/EchoService");
            HttpClient client = this.http(HttpClient.Version.HTTP_1_1, "");
            HttpRequest definitionRequest = this.get(URI.create(endpoint + "?wsdl"));
            HttpResponse<String> definition = this.send(client, definitionRequest, //
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, definition.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, definition.version());
            URI imported = URI.create(this.schemaLocation(definition.body()));
            HttpResponse<String> response = this.send(client, this.get(imported), //
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "application/xml"));
            assertTrue(response.body(), response.body().contains("urn:meteor:test:echo:types"));
            assertTrue(response.body(), response.body().contains("name=\"echo\""));
        }
    }

    @Test(timeout = 20000)
    public void directoryIsFetchedOverRealHttpsHttp2() throws Exception {
        // 目录和业务协议挂在同一个 HTTPS h2 端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        SoapTlsSupport.server(configuration, "h2", false) //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", "1.2") //
                .option("directoryEnabled", true) //
                .option("directoryPath", "/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部客户端经 TLS/ALPN 读取目录，页面保留可用的服务与 WSDL 链接。
            URI directory = this.endpoint(context, "/services");
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "");
            HttpResponse<String> response = this.send(client, this.get(directory), //
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertTrue(response.body(), response.body().contains("EchoService"));
            assertTrue(response.body(), response.body().contains("/services/EchoService?wsdl"));
        }
    }

    @Test(timeout = 20000)
    public void soap12FaultTravelsOverMutualTlsHttp2() throws Exception {
        // 双向 TLS h2 服务发布一个确定失败的 SOAP 1.2 方法。
        MetConfiguration configuration = this.server("1.2", "h2", true, "inline");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = value -> {
                throw new IllegalStateException("secure failure");
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 外部客户端携带身份，断言真实 HTTP/2 上返回 SOAP 1.2 Fault。
            URI endpoint = this.endpoint(context, "/services/EchoService");
            HttpRequest request = this.soapRequest(endpoint, "1.2", "echo", "secure-fault");
            HttpClient client = this.http(HttpClient.Version.HTTP_2, "client");
            HttpResponse<String> response = this.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertTrue(response.body(), response.body().contains("Fault"));
            assertTrue(response.body(), response.body().contains("secure failure"));
        }
    }

    @Test(timeout = 20000)
    public void oneWayReturnsBeforeBusinessOverMutualTlsHttp2() throws Exception {
        // SOAP 1.2 one-way 服务进入业务后保持阻塞，HTTP 层使用双向 TLS h2。
        BlockingSignalServiceImpl service = new BlockingSignalServiceImpl();
        MetConfiguration configuration = this.server("1.2", "h2", true, "inline");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(SignalService.class, service) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .register();

            // 回执不能等待业务完成，响应仍必须来自真实 HTTP/2 连接。
            URI endpoint = this.endpoint(context, "/services/SignalService");
            HttpRequest request = this.soapRequest(endpoint, "1.2", "signal", "secure-notice");
            try {
                HttpClient client = this.http(HttpClient.Version.HTTP_2, "client");
                HttpResponse<byte[]> response = this.send(client, request, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(202, response.statusCode());
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(0, response.body().length);
                assertTrue("Business method was not entered", service.awaitEntered(5, TimeUnit.SECONDS));
                assertFalse("Business method finished before acknowledgement", service.isFinished());
            } finally {
                service.release();
            }
            assertTrue("Business method did not finish", service.awaitFinished(5, TimeUnit.SECONDS));
            assertEquals("secure-notice", service.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void mtomRoundTripUsesMutualTlsHttp2() throws Exception {
        // 两端通过公开 Bootstrap 选择 SOAP 1.2 MTOM、HTTP/2 和双向 TLS。
        MetConfiguration incoming = this.server("1.2", "h2", true, "mtom");
        MetConfiguration outgoing = this.client("1.2", 2, "h2", true, "mtom");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<BinaryService> subscription = this.subscribeBinary(client);

            // 直接 byte[] 在 MTOM 请求和响应中通过 HTTPS 完整往返。
            InterAddress address = server.bindAddress("soap");
            BinaryService remote = client.getRsfClient(address).getRemote(subscription);
            byte[] expected = { 0, 1, 2, (byte) 0x80, (byte) 0xff };
            assertArrayEquals(expected, remote.echo(expected));
        }
    }

    @Test(timeout = 20000)
    public void swaRoundTripUsesHttpsHttp1() throws Exception {
        // 两端通过公开 Bootstrap 选择 SOAP 1.1 SwA、HTTP/1.1 和单向 TLS。
        MetConfiguration incoming = this.server("1.1", "http/1.1", false, "swa");
        MetConfiguration outgoing = this.client("1.1", 1, "http/1.1", false, "swa");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(BinaryService.class, new BinaryServiceImpl()) //
                    .aliasName("soap", "BinaryService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<BinaryService> subscription = this.subscribeBinary(client);

            // 直接 byte[] 在 SwA 请求和响应中通过 HTTPS 完整往返。
            InterAddress address = server.bindAddress("soap");
            BinaryService remote = client.getRsfClient(address).getRemote(subscription);
            byte[] expected = { 9, 8, 7, (byte) 0x80, (byte) 0xff };
            assertArrayEquals(expected, remote.echo(expected));
        }
    }

    private MetConfiguration server(String version, String alpn, boolean mutual, String attachmentMode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        SoapTlsSupport.server(configuration, alpn, mutual) //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("attachmentMode", attachmentMode) //
                .option("attachmentThreshold", 0);
        return configuration;
    }

    private MetConfiguration client(String version, int httpVersion, String alpn, boolean mutual, //
            String attachmentMode) {
        MetConfiguration configuration = new MetConfiguration() //
                .setDefaultProtocol("soap") //
                .setRequestTimeout(5000);
        SoapTlsSupport.client(configuration, httpVersion, alpn, mutual) //
                .protocol("soap").addressScheme("https") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("attachmentMode", attachmentMode) //
                .option("attachmentThreshold", 0);
        return configuration;
    }

    private MetBindInfo<BinaryService> subscribeBinary(MetContext context) throws Exception {
        return context.publisher() //
                .rsfService(BinaryService.class) //
                .aliasName("soap", "BinaryService") //
                .protocol("soap") //
                .register();
    }

    private SoapWsdlResource echoResource() throws Exception {
        URL wsdl = this.getClass().getResource("/wsdl/echo.wsdl");
        assertNotNull("Missing echo WSDL", wsdl);
        return new SoapWsdlResource(wsdl);
    }

    private String schemaLocation(String wsdl) {
        Matcher matcher = SCHEMA_LOCATION.matcher(wsdl);
        assertTrue("Missing WSDL schemaLocation", matcher.find());
        return matcher.group(1);
    }

    private HttpClient http(HttpClient.Version version, String identity) throws Exception {
        return HttpClient.newBuilder() //
                .sslContext(SoapTlsSupport.sslContext(identity, "trust")) //
                .version(version) //
                .build();
    }

    private HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri) //
                .GET() //
                .build();
    }

    private HttpRequest soapRequest(URI endpoint, String version, String operation, String value) {
        String serviceName = StringUtils.equals(operation, "signal") ? "SignalService" : "EchoService";
        String action = "urn:hasor:meteor:soap:" + serviceName + "/" + operation;
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint);
        if (StringUtils.equals("1.2", version)) {
            request.header("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        } else {
            request.header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + action + "\"");
        }
        byte[] envelope = this.envelope(version, serviceName, operation, value);
        return request.POST(HttpRequest.BodyPublishers.ofByteArray(envelope)).build();
    }

    private byte[] envelope(String version, String serviceName, String operation, String value) {
        String namespace = StringUtils.equals("1.2", version) //
                ? "http://www.w3.org/2003/05/soap-envelope" //
                : "http://schemas.xmlsoap.org/soap/envelope/";
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="%s">
                    <soap:Body>
                        <types:%s xmlns:types="urn:hasor:meteor:soap:%s">
                            <arg0>%s</arg0>
                        </types:%s>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(namespace, operation, serviceName, value, operation);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private <T> HttpResponse<T> send(HttpClient client, HttpRequest request, //
            HttpResponse.BodyHandler<T> handler) throws Exception {
        Future<HttpResponse<T>> pending = client.sendAsync(request, handler);
        try {
            return pending.get(5, TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
    }

    private URI endpoint(MetContext context, String path) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("https://localhost:" + port + path);
    }
}
