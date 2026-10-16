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
import java.time.Duration;
import java.util.Optional;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesFaultTest {
    @Test(timeout = 20000)
    public void serviceFailureReturnsSoapFault() throws Exception {
        // 发布一个总是失败的 SOAP 服务。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                throw new IllegalStateException("处理失败\n第二行");
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 使用原始 SOAP 请求触发业务异常。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("failure"))) //
                    .build();
            HttpClient http = HttpClient.newBuilder() //
                    .connectTimeout(Duration.ofSeconds(15)) //
                    .version(HttpClient.Version.HTTP_1_1) //
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 协议把失败映射为 HTTP 500 SOAP Fault，并保留可诊断信息。
            assertEquals(500, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/xml"));
            assertTrue(response.body(), response.body().contains("Fault"));
            assertTrue(response.body(), response.body().contains("soap:Server"));
            assertTrue(response.body(), response.body().contains("处理失败"));
            assertTrue(response.body(), response.body().contains("第二行"));
        }
    }

    @Test(timeout = 20000)
    public void responseOptionsDoNotOverrideSoapControlHeaders() throws Exception {
        // 配置与 SOAP 控制头同名的响应选项。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.setResponseOption("message", "internal message");
        configuration.setResponseOption("SOAPAction", "conflicting action");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 通过公开监听地址发起真实 SOAP 请求。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("headers"))) //
                    .build();
            HttpClient http = HttpClient.newBuilder() //
                    .connectTimeout(Duration.ofSeconds(15)) //
                    .version(HttpClient.Version.HTTP_1_1) //
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 内部消息与协议控制项不能覆盖 HTTP 响应头。
            assertEquals(200, response.statusCode());
            assertEquals(Optional.empty(), response.headers().firstValue("message"));
            assertEquals(Optional.empty(), response.headers().firstValue("soapaction"));
        }
    }

    @Test(timeout = 20000)
    public void businessResponseOptionBecomesHttpHeader() throws Exception {
        // 配置一个允许透传的业务响应选项。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.setResponseOption("X-Trace", "visible trace");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 通过公开监听地址发起真实 SOAP 请求。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("headers"))) //
                    .build();
            HttpClient http = HttpClient.newBuilder() //
                    .connectTimeout(Duration.ofSeconds(15)) //
                    .version(HttpClient.Version.HTTP_1_1) //
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 普通业务响应选项成为对应的 HTTP 响应头。
            assertEquals(200, response.statusCode());
            assertEquals(Optional.of("visible trace"), response.headers().firstValue("x-trace"));
        }
    }

    private byte[] echoEnvelope(String message) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </m:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(message);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}
