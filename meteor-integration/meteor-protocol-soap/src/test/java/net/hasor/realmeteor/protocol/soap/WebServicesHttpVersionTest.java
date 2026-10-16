/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.SoapResponseUtils;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebServicesHttpVersionTest {
    @Test(timeout = 15000)
    public void soap11UsesCleartextHttp1() throws Exception {
        // 从公开 Bootstrap 配置 SOAP 1.1 的明文 HTTP 监听。
        MetConfiguration configuration = this.incoming("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishEcho(context);

            // 外部 JDK 客户端固定使用 HTTP/1.1，响应同时证明版本和业务结果。
            URI endpoint = this.endpoint(context);
            HttpResponse<String> response = this.invoke(endpoint, "1.1", HttpClient.Version.HTTP_1_1, "soap11-h1");
            assertEquals(response.body(), 200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap11-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 15000)
    public void soap12UsesCleartextHttp1() throws Exception {
        // 从公开 Bootstrap 配置 SOAP 1.2 的明文 HTTP 监听。
        MetConfiguration configuration = this.incoming("1.2");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishEcho(context);

            // 外部 JDK 客户端固定使用 HTTP/1.1，SOAP 1.2 不依赖 HTTP/2。
            URI endpoint = this.endpoint(context);
            HttpResponse<String> response = this.invoke(endpoint, "1.2", HttpClient.Version.HTTP_1_1, "soap12-h1");
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals("soap12-h1", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 15000)
    public void soap11UsesH2cUpgrade() throws Exception {
        // SOAP 1.1 服务保持普通明文监听，由连接器识别 h2c Upgrade。
        MetConfiguration configuration = this.incoming("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishEcho(context);

            // JDK HTTP/2 客户端发起 Upgrade，必须返回真实 HTTP/2 响应。
            URI endpoint = this.endpoint(context);
            HttpResponse<String> response = this.invoke(endpoint, "1.1", HttpClient.Version.HTTP_2, "soap11-h2c");
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap11-h2c", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 15000)
    public void soap12UsesH2cUpgrade() throws Exception {
        // SOAP 1.2 服务保持普通明文监听，由连接器识别 h2c Upgrade。
        MetConfiguration configuration = this.incoming("1.2");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            this.publishEcho(context);

            // JDK HTTP/2 客户端发起 Upgrade，不能静默回退为 HTTP/1.1。
            URI endpoint = this.endpoint(context);
            HttpResponse<String> response = this.invoke(endpoint, "1.2", HttpClient.Version.HTTP_2, "soap12-h2c");
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("soap12-h2c", SoapResponseUtils.returnText(response.body()));
        }
    }

    @Test(timeout = 15000)
    public void soap11UsesH2PriorKnowledge() throws Exception {
        // 服务端自动识别 HTTP/2 preface，客户端端点只创建 HTTP/2 通道。
        MetConfiguration incoming = this.incoming("1.1");
        MetConfiguration outgoing = this.outgoing("1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            this.publishEcho(server);
            MetBindInfo<EchoService> subscription = this.subscribeEcho(client);

            // 成功调用即证明 prior knowledge 通道完成，HTTP/2 通道没有 HTTP/1 回退路径。
            InterAddress address = server.bindAddress("soap");
            EchoService remote = client.getRsfClient(address).getRemote(subscription);
            assertEquals("soap11-prior", remote.echo("soap11-prior"));
        }
    }

    @Test(timeout = 15000)
    public void soap12UsesH2PriorKnowledge() throws Exception {
        // 服务端自动识别 HTTP/2 preface，客户端端点只创建 HTTP/2 通道。
        MetConfiguration incoming = this.incoming("1.2");
        MetConfiguration outgoing = this.outgoing("1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            this.publishEcho(server);
            MetBindInfo<EchoService> subscription = this.subscribeEcho(client);

            // SOAP 1.2 在明文 prior knowledge 通道上完成普通 request-response。
            InterAddress address = server.bindAddress("soap");
            EchoService remote = client.getRsfClient(address).getRemote(subscription);
            assertEquals("soap12-prior", remote.echo("soap12-prior"));
        }
    }

    private MetConfiguration incoming(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version);
        return configuration;
    }

    private MetConfiguration outgoing(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .option("httpVersion", 2) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version);
        return configuration;
    }

    private void publishEcho(MetContext context) throws Exception {
        context.publisher() //
                .rsfService(EchoService.class, new EchoServiceImpl()) //
                .aliasName("soap", "EchoService") //
                .protocol("soap") //
                .register();
    }

    private MetBindInfo<EchoService> subscribeEcho(MetContext context) throws Exception {
        return context.publisher() //
                .rsfService(EchoService.class) //
                .aliasName("soap", "EchoService") //
                .protocol("soap") //
                .register();
    }

    private HttpResponse<String> invoke(URI endpoint, String version, //
            HttpClient.Version httpVersion, String value) throws Exception {
        HttpClient client = HttpClient.newBuilder() //
                .version(httpVersion) //
                .build();
        HttpRequest request = this.request(endpoint, version, value);
        return this.send(client, request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(URI endpoint, String version, String value) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint);
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

    private <T> HttpResponse<T> send(HttpClient client, HttpRequest request, //
            HttpResponse.BodyHandler<T> handler) throws Exception {
        Future<HttpResponse<T>> pending = client.sendAsync(request, handler);
        try {
            return pending.get(5, TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
    }

    private URI endpoint(MetContext context) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/EchoService");
    }
}
