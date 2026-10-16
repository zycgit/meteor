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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.hasor.example.protocol.soap.FieldService;
import net.hasor.example.protocol.soap.FieldServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesQNameTest {
    @Test
    public void meteorClientUsesEffectiveFieldQNames() throws Exception {
        // 启动服务端和客户端，发布包含 qualified、unqualified 与 ref 字段的 WSDL。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            SoapWsdlResource resource = this.resource();
            server.publisher() //
                    .rsfService(FieldService.class, new FieldServiceImpl()) //
                    .aliasName("soap", "FieldService") //
                    .protocol("soap") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<FieldService> subscribed = client.publisher() //
                    .rsfService(FieldService.class) //
                    .aliasName("soap", "FieldService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();

            // 公开客户端经过真实 HTTP 往返，验证请求参数和返回 ref 均使用契约 QName。
            MetClient caller = client.getRsfClient(server.getListenAddress("soap"));
            FieldService remote = caller.getRemote(subscribed);
            assertEquals("plain:qualified", remote.combine("plain", "qualified"));
        }
    }

    @Test
    public void wrongParameterNamespaceIsRejectedBeforeInvocation() throws Exception {
        // 发布可观察调用次数的服务，参数 plain 在 WSDL 中明确属于空命名空间。
        MetConfiguration configuration = this.configuration(true);
        FieldServiceImpl service = new FieldServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(FieldService.class, service) //
                    .aliasName("soap", "FieldService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();

            // 使用相同 localName、错误命名空间发送 plain 参数。
            int port = context.getListenAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/FieldService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:meteor:test:fields:combine\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.wrongNamespaceEnvelope())) //
                    .build();
            HttpClient http = HttpClient.newBuilder() //
                    .connectTimeout(Duration.ofSeconds(15)) //
                    .version(HttpClient.Version.HTTP_1_1) //
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // QName 不匹配在业务调用前失败，不允许降级为仅按 localName 取值。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("SOAP Body does not match its XML Schema"));
            assertEquals(0, service.getInvocationCount());
        }
    }

    private MetConfiguration configuration(boolean bind) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        if (bind) {
            configuration.connector("http") //
                    .bind("127.0.0.1", 0) //
                    .protocol("soap") //
                    .contextPath("/services");
        } else {
            configuration.connector("http") //
                    .protocol("soap") //
                    .contextPath("/services");
        }
        return configuration;
    }

    private SoapWsdlResource resource() throws Exception {
        URL wsdl = this.getClass().getResource("/wsdl/field-namespaces.wsdl");
        assertNotNull("Missing field namespace WSDL", wsdl);
        return new SoapWsdlResource(wsdl);
    }

    private byte[] wrongNamespaceEnvelope() {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:fields="urn:meteor:test:fields">
                    <soap:Body>
                        <fields:combine>
                            <fields:plain>wrong namespace</fields:plain>
                            <fields:qualified>qualified</fields:qualified>
                        </fields:combine>
                    </soap:Body>
                </soap:Envelope>
                """;
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}
