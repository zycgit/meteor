/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 服务的发布范围决定 Triple 在同一监听端口上的上下文可见性。 */
public class TripleContextScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();


    @Test(timeout = 15000)
    public void unspecifiedServiceContextUsesTheDefaultMount() throws Exception {
        // 同一端点提供默认和附加挂载，服务未指定上下文。
        MetConfiguration configuration = this.configuration("/rpc", "/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 标准 Triple JSON 请求通过默认上下文访问实际业务对象。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> response = this.echo(port, "/rpc");
            assertEquals(200, response.statusCode());
            JsonNode reply = JSON.readTree(response.body());
            assertEquals("context 中文", reply.path("text").asText());
            assertEquals(42, reply.path("number").asInt());
        }
    }

    @Test(timeout = 15000)
    public void unspecifiedServiceContextIsHiddenFromAdditionalMounts() throws Exception {
        // 附加挂载存在，但未被服务选择，不应默认暴露业务对象。
        MetConfiguration configuration = this.configuration("/rpc", "/private");
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 客户端收到协议级不可调用结果，服务实现没有执行。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> response = this.echo(port, "/private");
            assertEquals(404, response.statusCode());
            assertEquals(0, implementation.getCalls());
        }
    }

    @Test(timeout = 15000)
    public void explicitServiceContextExposesTheSelectedAdditionalMount() throws Exception {
        // 服务通过发布 API 选择附加上下文，不改变端口和协议配置。
        MetConfiguration configuration = this.configuration("/rpc", "/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json") //
                    .contextPathByPrefix("triple", "/private").register();

            // 附加上下文可以完成一次真实 Triple RPC。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> response = this.echo(port, "/private");
            assertEquals(200, response.statusCode());
            assertEquals(42, JSON.readTree(response.body()).path("number").asInt());
        }
    }

    @Test(timeout = 15000)
    public void explicitServiceContextReplacesTheDefaultSelection() throws Exception {
        // 显式选择附加上下文后，默认上下文不再获得该服务的可见性。
        MetConfiguration configuration = this.configuration("/rpc", "/private");
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json") //
                    .contextPathByPrefix("triple", "/private").register();

            // 默认路径返回 Triple 错误，而不是穿透到额外挂载的服务。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> response = this.echo(port, "/rpc");
            assertEquals(404, response.statusCode());
            assertEquals(0, implementation.getCalls());
        }
    }

    @Test(timeout = 15000)
    public void explicitMultipleContextsShareOneServiceAndOnePort() throws Exception {
        // 一个服务显式选择两个上下文，两个入口共用同一个 HTTP 监听。
        MetConfiguration configuration = this.configuration("/rpc", "/private");
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json") //
                    .contextPathByPrefix("triple", "/rpc", "/private").register();

            // 两个路径均由当前端口进入同一个发布对象。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> first = this.echo(port, "/rpc");
            HttpResponse<String> second = this.echo(port, "/private");
            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            assertEquals(first.body(), second.body());
            assertEquals(2, implementation.getCalls());
        }
    }

    @Test(timeout = 15000)
    public void nestedMountUsesTheLongestMatchingContext() throws Exception {
        // 默认上下文是父路径，业务服务仅发布到更长的嵌套挂载。
        MetConfiguration configuration = this.configuration("/rpc", "/rpc/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json") //
                    .contextPathByPrefix("triple", "/rpc/private").register();

            // 路由应移除完整嵌套前缀，才能解析正确的服务名与方法名。
            int port = server.getListenAddress("triple").getPort();
            HttpResponse<String> response = this.echo(port, "/rpc/private");
            assertEquals(200, response.statusCode());
            assertEquals("context 中文", JSON.readTree(response.body()).path("text").asText());
        }
    }

    private MetConfiguration configuration(String defaultPath, String additionalPath) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("triple") //
                .contextPath(defaultPath).addContextPath(additionalPath).option("httpMode", "json");
        return configuration;
    }

    private HttpResponse<String> echo(int port, String contextPath) throws Exception {
        // 对端只使用 JDK HTTP 与独立 JSON 编码，避免复用插件的请求构造逻辑。
        String path = contextPath + "/meteor.triple.demo.TripleDemoService/echo";
        URI address = URI.create("http://127.0.0.1:" + port + path);
        String body = "[{\"text\":\"context 中文\",\"number\":41}]";
        HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json").header("tri-protocol-version", "1") //
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)) //
                .version(HttpClient.Version.HTTP_1_1).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
