/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XmlRpcContextScenarioTest {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test(timeout = 15000)
    public void unspecifiedServiceContextUsesOnlyTheDefault() throws Exception {
        // 一个监听端口声明默认与附加挂载，服务按发布配置选择暴露位置。
        MetConfiguration configuration = this.configuration();
        AtomicInteger calls = new AtomicInteger();
        XmlRpcDemoServiceImpl implementation = new XmlRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, implementation) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 被选中的路径能调用；另一个已挂载路径不能越过服务暴露限制。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><string>selected</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest selected = this.post(port, "/rpc", body);
            HttpResponse<String> accepted = this.http.send(selected, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode());
            assertTrue(accepted.body().contains("<string>selected</string>"));

            HttpRequest other = this.post(port, "/private", body);
            HttpResponse<String> rejected = this.http.send(other, HttpResponse.BodyHandlers.ofString());
            assertTrue(rejected.body().contains("<fault>"));
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitServiceContextExcludesTheDefault() throws Exception {
        // 一个监听端口声明默认与附加挂载，服务按发布配置选择暴露位置。
        MetConfiguration configuration = this.configuration();
        AtomicInteger calls = new AtomicInteger();
        XmlRpcDemoServiceImpl implementation = new XmlRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, implementation) //
                    .aliasName("xmlrpc", "demo") //
                    .contextPathByPrefix("xmlrpc", "/private") //
                    .register();

            // 被选中的路径能调用；另一个已挂载路径不能越过服务暴露限制。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><string>selected</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest selected = this.post(port, "/private", body);
            HttpResponse<String> accepted = this.http.send(selected, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode());
            assertTrue(accepted.body().contains("<string>selected</string>"));

            HttpRequest other = this.post(port, "/rpc", body);
            HttpResponse<String> rejected = this.http.send(other, HttpResponse.BodyHandlers.ofString());
            assertTrue(rejected.body().contains("<fault>"));
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitMultipleContextsExposeTheServiceOnOnePort() throws Exception {
        // 同一 Java 服务显式选择默认和附加挂载。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .contextPathByPrefix("xmlrpc", "/rpc", "/private") //
                    .register();

            // 两个挂载地址共享端口，均调用同一个已发布服务。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>demo.current</methodName></methodCall>
                    """;
            HttpRequest defaultRequest = this.post(port, "/rpc", body);
            HttpResponse<String> defaults = this.http.send(defaultRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, defaults.statusCode());
            assertTrue(defaults.body().contains("<string>ready</string>"));

            HttpRequest extraRequest = this.post(port, "/private", body);
            HttpResponse<String> extra = this.http.send(extraRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, extra.statusCode());
            assertTrue(extra.body().contains("<string>ready</string>"));
        }
    }

    @Test(timeout = 15000)
    public void mountedServiceOptionAllowsUnqualifiedMethodNames() throws Exception {
        // 单服务端点通过协议选项绑定别名，外部客户端只发送方法名。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/calculator").option("service", "calculator");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "calculator") //
                    .register();

            // 标准 XML-RPC 客户端无需了解 Meteor 的服务分组或版本。
            int port = server.getListenAddress("xmlrpc").getPort();
            String body = """
                    <methodCall><methodName>subtract</methodName><params>
                    <param><value><int>13</int></value></param>
                    <param><value><int>8</int></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = this.post(port, "/calculator", body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<int>5</int>"));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc").addContextPath("/private");
        return configuration;
    }

    private HttpRequest post(int port, String path, String body) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "text/xml") //
                .POST(HttpRequest.BodyPublishers.ofString(body)) //
                .build();
    }
}
