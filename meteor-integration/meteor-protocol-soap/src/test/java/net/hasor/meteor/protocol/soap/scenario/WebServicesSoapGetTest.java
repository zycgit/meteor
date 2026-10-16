/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.scenario.example.AttachmentHttpProxy;
import net.hasor.meteor.protocol.soap.scenario.example.SoapStatusService;
import net.hasor.meteor.protocol.soap.scenario.example.SoapStatusServiceImpl;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesSoapGetTest {
    @Test(timeout = 20000)
    public void meteorClientUsesTheAnnotatedGetOperation() throws Exception {
        // 客户端与服务端声明相同的只读接口，SOAP 1.2 自动选用 GET 绑定。
        MetConfiguration serverConfig = this.configuration("1.2");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("http").protocol("soap") //
                .contextPath("/services").option("soapVersion", "1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();
            MetBindInfo<SoapStatusService> service = client.publisher().rsfService(SoapStatusService.class) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 字节转发代理观测 HTTP 方法，避免 POST 也能调用成功掩盖 GET 发送路径。
            int port = server.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/Status");
            AttachmentHttpProxy observed = new AttachmentHttpProxy(endpoint);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                proxy.createContext("/services/Status", observed);
                proxy.start();
                int proxyPort = proxy.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", proxyPort, "default");
                SoapStatusService remote = client.getRsfClient(address).getRemote(service);
                assertEquals("ready", remote.status());
                assertEquals("GET", observed.getRequestMethod());
                assertEquals(1, implementation.getInvocations());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void annotatedReadOnlyMethodReturnsSoap12Representation() throws Exception {
        // 显式声明只读查询，通过 SOAP 1.2 GET 获取 SOAP 响应。
        MetConfiguration configuration = this.configuration("1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=status");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .GET().build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(1, implementation.getInvocations());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, contentType.startsWith("application/soap+xml"));
            assertTrue(response.body(), response.body().contains("http://www.w3.org/2003/05/soap-envelope"));
            assertTrue(response.body(), response.body().contains("ready"));
            assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        }
    }

    @Test(timeout = 20000)
    public void unannotatedMethodIsNotExposedAsGet() throws Exception {
        // 普通无参方法没有 SoapGet 标记，GET 不能执行业务。
        MetConfiguration configuration = this.configuration("1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=internalStatus");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .GET().build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals(0, implementation.getInvocations());
        }
    }

    @Test(timeout = 20000)
    public void unknownGetOperationReturnsNotFound() throws Exception {
        // 不存在的查询操作返回明确的资源不存在状态。
        MetConfiguration configuration = this.configuration("1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=missing");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .GET().build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
            assertEquals(0, implementation.getInvocations());
        }
    }

    @Test(timeout = 20000)
    public void headDoesNotExecuteTheReadOnlyOperation() throws Exception {
        // HEAD 不通过查询入口触发 Java 方法执行。
        MetConfiguration configuration = this.configuration("1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=status");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals(0, implementation.getInvocations());
        }
    }

    @Test(timeout = 20000)
    public void soap11DoesNotExposeTheSoap12GetBinding() throws Exception {
        // 同一 Java 接口挂载 SOAP 1.1 时保持其 POST 调用方式。
        MetConfiguration configuration = this.configuration("1.1");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=status");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .GET().build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals(0, implementation.getInvocations());
        }
    }

    @Test(timeout = 20000)
    public void getWithRequestBodyIsRejectedBeforeInvocation() throws Exception {
        // SOAP response 消息交换模式的 GET 不携带请求 SOAP 正文。
        MetConfiguration configuration = this.configuration("1.2");
        SoapStatusServiceImpl implementation = new SoapStatusServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(SoapStatusService.class, implementation) //
                    .aliasName("soap", "Status").protocol("soap").register();

            // 通过真实 HTTP 入口请求，检查状态与业务是否实际执行。
            int port = context.bindAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/Status?method=status");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15)) //
                    .method("GET", HttpRequest.BodyPublishers.ofString("unexpected")).build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertEquals(0, implementation.getInvocations());
        }
    }

    private MetConfiguration configuration(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").option("soapVersion", version);
        return configuration;
    }

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1).build();
    }
}
