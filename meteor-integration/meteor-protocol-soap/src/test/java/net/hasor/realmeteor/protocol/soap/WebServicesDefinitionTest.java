/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesDefinitionTest {
    @Test
    public void wsdlIsPublishedAtTheServiceEndpoint() throws Exception {
        // 配置 SOAP 默认挂载并启动 HTTP 监听。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 通过真实 HTTP GET 读取定义页。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = this.endpoint(port, "/services/EchoService");
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?wsdl")) //
                    .timeout(Duration.ofSeconds(15)) //
                    .GET() //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // WSDL 返回 XML，并把服务地址和 XSD 引用改写到当前端口。
            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/xml"));
            assertTrue(response.body(), response.body().contains("location=\"" + endpoint + "\""));
            assertTrue(response.body(), response.body().contains(endpoint + "?xsd=echo-types.xsd"));
        }
    }

    @Test
    public void xsdImportIsPublishedAtTheServiceEndpoint() throws Exception {
        // 配置并启动 SOAP 服务，XSD 与 WSDL 绑定到同一服务端点。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 通过 WSDL 改写后的查询地址读取真实 XSD。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = this.endpoint(port, "/services/EchoService?xsd=echo-types.xsd");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .GET() //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // XSD 保留目标命名空间与公开的 echo 包装元素。
            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "application/xml"));
            assertTrue(response.body(), response.body().contains("urn:meteor:test:echo:types"));
            assertTrue(response.body(), response.body().contains("name=\"echo\""));
        }
    }

    @Test
    public void headReturnsDefinitionHeadersWithoutABody() throws Exception {
        // 配置并启动 SOAP 服务，定义页面沿用业务端点。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 对 WSDL 发起真实 HEAD 请求。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = this.endpoint(port, "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());

            // HEAD 保留定义类型，但线上不返回正文。
            assertEquals(200, response.statusCode());
            assertEquals(0, response.body().length);
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/xml"));
        }
    }

    @Test
    public void unknownDefinitionResourceReturnsNotFound() throws Exception {
        // 配置并启动只绑定 echo-types.xsd 的 SOAP 服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.echoResource()) //
                    .register();

            // 请求未绑定的 XSD 名称。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = this.endpoint(port, "/services/EchoService?xsd=missing.xsd");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .GET() //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 未知定义资源以 HTTP 404 和空正文结束。
            assertEquals(404, response.statusCode());
            assertEquals(0, response.body().length);
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private SoapWsdlResource echoResource() throws Exception {
        URL wsdl = this.getClass().getResource("/wsdl/echo.wsdl");
        assertNotNull("Missing echo WSDL", wsdl);
        return new SoapWsdlResource(wsdl);
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private URI endpoint(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
