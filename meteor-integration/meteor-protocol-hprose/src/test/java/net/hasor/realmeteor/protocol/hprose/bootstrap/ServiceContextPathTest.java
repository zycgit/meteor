/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.example.protocol.hprose.TextService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ServiceContextPathTest extends BootstrapTestSupport {
    @Test
    public void implicitServiceUsesOnlyTheDefaultContext() throws Exception {
        // 接入点声明默认和附加挂载，服务不显式选择路径。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 同一服务在默认挂载可调用，附加挂载必须返回协议禁止错误。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] call = this.callFrame("echo_echo", "default");
            HttpRequest allowedRequest = this.post(port, "/hprose", call);
            HttpResponse<byte[]> allowed = http.send(allowedRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("default", this.readFrame(allowed, 'R', String.class));

            HttpRequest deniedRequest = this.post(port, "/private", call);
            HttpResponse<byte[]> denied = http.send(deniedRequest, HttpResponse.BodyHandlers.ofByteArray());
            String error = this.readFrame(denied, 'E', String.class);
            assertEquals(ProtocolStatus.Forbidden, new ObjectMapper().readTree(error).path("status").asInt());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void explicitlySelectedExtraContextExcludesTheDefault() throws Exception {
        // 服务只选择附加挂载，声明附加路径不会自动保留默认路径。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .contextPathByPrefix("hprose", "/private") //
                    .register();

            // 附加挂载调用成功，默认挂载的同名函数不能执行该服务。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] call = this.callFrame("echo_echo", "private");
            HttpRequest allowedRequest = this.post(port, "/private", call);
            HttpResponse<byte[]> allowed = http.send(allowedRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("private", this.readFrame(allowed, 'R', String.class));

            HttpRequest deniedRequest = this.post(port, "/hprose", call);
            HttpResponse<byte[]> denied = http.send(deniedRequest, HttpResponse.BodyHandlers.ofByteArray());
            String error = this.readFrame(denied, 'E', String.class);
            assertEquals(ProtocolStatus.Forbidden, new ObjectMapper().readTree(error).path("status").asInt());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void explicitlySelectedMultipleContextsExposeTheSameService() throws Exception {
        // 同一个服务显式选择默认与附加路径，共享一个 HTTP 监听地址。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .contextPathByPrefix("hprose", "/hprose", "/private") //
                    .register();

            // 两个挂载都能调用同一实现，各自返回本次请求参数。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] defaultCall = this.callFrame("echo_echo", "default");
            HttpRequest defaultsRequest = this.post(port, "/hprose", defaultCall);
            HttpResponse<byte[]> defaults = http.send(defaultsRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("default", this.readFrame(defaults, 'R', String.class));

            byte[] privateCall = this.callFrame("echo_echo", "private");
            HttpRequest extraRequest = this.post(port, "/private", privateCall);
            HttpResponse<byte[]> extra = http.send(extraRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("private", this.readFrame(extra, 'R', String.class));
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void functionListsContainOnlyServicesExposedOnTheRequestedContext() throws Exception {
        // 两个服务分属默认和附加挂载，使用不同别名观察函数发现范围。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value).name("public") //
                    .aliasName("Hprose", "public") //
                    .register();
            server.publisher() //
                    .rsfService(TextService.class, value -> value).name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/private") //
                    .register();

            // 从两个挂载分别发现函数，返回列表不能泄漏另一挂载的别名。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] discovery = { 'z' };
            HttpRequest defaultsRequest = this.post(port, "/hprose", discovery);
            HttpResponse<byte[]> defaults = http.send(defaultsRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(new String[] { "public_echo" }, this.readFrame(defaults, 'F', String[].class));

            HttpRequest extraRequest = this.post(port, "/private", discovery);
            HttpResponse<byte[]> extra = http.send(extraRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(new String[] { "private_echo" }, this.readFrame(extra, 'F', String[].class));
        }
    }

    @Test
    public void nestedContextDoesNotExposeServicesFromTheDefaultContext() throws Exception {
        // 默认路径包含独立的子挂载，默认服务不能穿透到该子挂载。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/api").addContextPath("/api/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 默认挂载允许普通子路径，但更具体的子挂载独立执行暴露检查。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] call = this.callFrame("echo_echo", "nested");
            HttpRequest allowedRequest = this.post(port, "/api/child", call);
            HttpResponse<byte[]> allowed = http.send(allowedRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("nested", this.readFrame(allowed, 'R', String.class));

            HttpRequest nestedRequest = this.post(port, "/api/private/child", call);
            HttpResponse<byte[]> nested = http.send(nestedRequest, HttpResponse.BodyHandlers.ofByteArray());
            String error = this.readFrame(nested, 'E', String.class);
            assertEquals(ProtocolStatus.Forbidden, new ObjectMapper().readTree(error).path("status").asInt());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void similarPrefixWithoutAPathBoundaryDoesNotMatchTheContext() throws Exception {
        // 发布默认挂载服务，记录相似前缀请求是否意外进入业务。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/api").addContextPath("/api/private");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 相似前缀不满足路径段边界，不能落入默认挂载。
            int port = server.getListenAddress("hprose").getPort();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            byte[] call = this.callFrame("echo_echo", "sibling");
            HttpRequest siblingRequest = this.post(port, "/api-private", call);
            HttpResponse<byte[]> sibling = http.send(siblingRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, sibling.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void clientUsesEachSubscribedServicesSelectedOutboundContext() throws Exception {
        // 两端声明相同挂载，客户端为不同订阅分别选择默认路径和附加路径。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("hprose");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("hprose");
        outgoing.connector("http") //
                .protocol("hprose").contextPath("/hprose").addContextPath("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 通过公开过滤器记录服务端收到的真实 HTTP 请求路径。
            Map<String, String> observedPaths = new ConcurrentHashMap<>();
            server.publisher().bindFilter("paths", (request, response, chain) -> {
                String argument = (String) request.getParameterObject()[0];
                observedPaths.put(argument, request.getOption("Location"));
                chain.doFilter(request, response);
            });
            server.publisher() //
                    .rsfService(TextService.class, value -> value).name("public") //
                    .aliasName("Hprose", "public") //
                    .register();
            server.publisher() //
                    .rsfService(TextService.class, value -> value).name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/private") //
                    .register();
            MetBindInfo<TextService> defaults = client.publisher() //
                    .rsfService(TextService.class).name("public") //
                    .aliasName("Hprose", "public") //
                    .register();
            MetBindInfo<TextService> extra = client.publisher() //
                    .rsfService(TextService.class).name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/private") //
                    .register();

            // 共享同一远端地址发起真实 RPC，每个订阅独立决定发出请求的路径。
            MetClient remote = client.getRsfClient(server.getListenAddress("hprose"));
            assertEquals("private", remote.getRemote(extra).echo("private"));
            assertEquals("default", remote.getRemote(defaults).echo("default"));
            assertEquals("/private", observedPaths.get("private"));
            assertEquals("/hprose", observedPaths.get("default"));
        }
    }

    private byte[] callFrame(String function, String value) throws Exception {
        // 外部对端独立编码 Hprose 方法名和参数引用表。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('C');
        HproseWriter writer = new HproseWriter(output);
        writer.writeString(function);
        writer.reset();
        writer.writeArray(new Object[] { value });
        output.write('z');
        return output.toByteArray();
    }

    private HttpRequest post(int port, String path, byte[] body) {
        URI address = URI.create("http://127.0.0.1:" + port + path);
        return HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)) //
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private <T> T readFrame(HttpResponse<byte[]> response, int tag, Class<T> type) throws Exception {
        // 检查 HTTP 和 Hprose 帧边界，再按当前场景指定的响应类型解码。
        assertEquals(200, response.statusCode());
        ByteArrayInputStream input = new ByteArrayInputStream(response.body());
        assertEquals(tag, input.read());
        T result = new HproseReader(input).unserialize(type);
        assertEquals('z', input.read());
        assertEquals(-1, input.read());
        return result;
    }
}
