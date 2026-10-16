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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.SoapResponseUtils;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesContextPathTest {
    @Test
    public void selectedContextDefinitionIsIsolatedOnTheSharedListener() throws Exception {
        // 同一 HTTP 监听挂载两个 SOAP context，默认使用 public。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后，把服务显式发布到 private context。
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .contextPathByPrefix("soap", "/private") //
                    .register();

            // 选择的 private context 返回生成定义，并保留正确的服务地址。
            int port = context.bindAddress("soap").getPort();
            URI privateWsdl = URI.create("http://127.0.0.1:" + port + "/private/EchoService?wsdl");
            HttpClient http = this.httpClient();
            HttpResponse<String> allowed = http.send(this.get(privateWsdl), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertTrue(allowed.body(), allowed.body().contains("/private/EchoService"));

            // 服务只在选择的 context 可见，默认 context 不会泄漏定义页。
            URI publicWsdl = URI.create("http://127.0.0.1:" + port + "/public/EchoService?wsdl");
            HttpResponse<String> denied = http.send(this.get(publicWsdl), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, denied.statusCode());
        }
    }

    @Test
    public void defaultContextDefinitionIsHiddenFromAdditionalContext() throws Exception {
        // 同一监听提供 public 和 private，服务保留默认挂载选择。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            // 普通 Java 服务直接生成契约，并仅继承默认的 public context。
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 默认 context 可获取定义，返回的服务地址也保留该挂载路径。
            int port = context.bindAddress("soap").getPort();
            URI publicWsdl = URI.create("http://127.0.0.1:" + port + "/public/EchoService?wsdl");
            HttpClient http = this.httpClient();
            HttpResponse<String> allowed = http.send(this.get(publicWsdl), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertTrue(allowed.body(), allowed.body().contains("/public/EchoService"));

            // 额外挂载不会自动获得默认服务的定义。
            URI privateWsdl = URI.create("http://127.0.0.1:" + port + "/private/EchoService?wsdl");
            HttpResponse<String> denied = http.send(this.get(privateWsdl), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, denied.statusCode());
        }
    }

    @Test
    public void selectedContextInvocationIsIsolatedOnTheSharedListener() throws Exception {
        // 同一监听提供两个 context，通过调用计数观察业务隔离。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 显式选择 private 后，服务不再继承 public 的默认挂载。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .contextPathByPrefix("soap", "/private") //
                    .register();

            // 从 public 发起相同的合法 SOAP 请求，业务方法不得执行。
            int port = context.bindAddress("soap").getPort();
            URI publicEndpoint = URI.create("http://127.0.0.1:" + port + "/public/EchoService");
            HttpClient http = this.httpClient();
            HttpRequest deniedRequest = this.echoRequest(publicEndpoint);
            HttpResponse<String> denied = http.send(deniedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, denied.statusCode());
            assertEquals(0, invocations.get());

            // 同一监听的 private 可正常调用，隔离不会阻止目标 context。
            URI privateEndpoint = URI.create("http://127.0.0.1:" + port + "/private/EchoService");
            HttpRequest allowedRequest = this.echoRequest(privateEndpoint);
            HttpResponse<String> allowed = http.send(allowedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertEquals("context isolation", SoapResponseUtils.returnText(allowed.body()));
            assertEquals(1, invocations.get());
        }
    }

    @Test
    public void defaultContextInvocationIsHiddenFromAdditionalContext() throws Exception {
        // 默认服务保留 public 挂载，通过调用计数检查 private 不会泄漏服务。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 通过公开发布入口注册普通 Java 服务，不指定额外的 context。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 额外挂载上的合法 SOAP 请求找不到默认服务，业务调用计数保持为零。
            int port = context.bindAddress("soap").getPort();
            URI privateEndpoint = URI.create("http://127.0.0.1:" + port + "/private/EchoService");
            HttpClient http = this.httpClient();
            HttpRequest deniedRequest = this.echoRequest(privateEndpoint);
            HttpResponse<String> denied = http.send(deniedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, denied.statusCode());
            assertEquals(0, invocations.get());

            // public 仍可完成同一业务请求，额外 context 不影响默认挂载。
            URI publicEndpoint = URI.create("http://127.0.0.1:" + port + "/public/EchoService");
            HttpRequest allowedRequest = this.echoRequest(publicEndpoint);
            HttpResponse<String> allowed = http.send(allowedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertEquals("context isolation", SoapResponseUtils.returnText(allowed.body()));
            assertEquals(1, invocations.get());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/public") //
                .addContextPath("/private");
        return configuration;
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(15)) //
                .GET() //
                .build();
    }

    private HttpRequest echoRequest(URI endpoint) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <tns:echo xmlns:tns="urn:hasor:meteor:soap:EchoService">
                            <arg0>context isolation</arg0>
                        </tns:echo>
                    </soap:Body>
                </soap:Envelope>
                """;
        return HttpRequest.newBuilder(endpoint) //
                .timeout(Duration.ofSeconds(15)) //
                .header("Content-Type", "text/xml; charset=utf-8") //
                .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                .build();
    }
}
