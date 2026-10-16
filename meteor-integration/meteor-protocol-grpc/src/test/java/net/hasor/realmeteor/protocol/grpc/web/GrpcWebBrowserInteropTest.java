/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方 grpc-web 生成客户端在真实 Chromium 中调用公开 bootstrap 发布的服务。 */
public class GrpcWebBrowserInteropTest {

    @Test(timeout = 40000)
    public void officialBrowserUsesBinaryUnary() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 浏览器使用二进制 Protobuf 普通调用。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "binary");
                assertEquals("中文🙂 <&>", result.path("value").asText());
                assertEquals(7, result.path("count").asInt());
                assertTrue(this.hasPreflight(result));
            }
        }
    }

    @Test(timeout = 40000)
    public void officialBrowserUsesTextUnary() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 浏览器使用 base64 文本 Protobuf 普通调用。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "text");
                assertEquals("中文🙂 <&>", result.path("value").asText());
                assertEquals(7, result.path("count").asInt());
            }
        }
    }

    @Test(timeout = 40000)
    public void officialBrowserReceivesTextServerStream() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 官方客户端按消息接收文本响应流及结束状态。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "stream");
                JsonNode values = result.path("values");
                assertEquals(3, values.size());
                for (int index = 0; index < values.size(); index++) {
                    assertEquals("中文🙂", values.get(index).path("value").asText());
                    assertEquals(index, values.get(index).path("count").asInt());
                }
                assertEquals(0, result.path("status").path("code").asInt());
                assertTrue(result.path("ended").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void officialBrowserReceivesApplicationStatus() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 协议错误以标准状态码和 Unicode 描述交付浏览器。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "fault");
                assertEquals(7, result.path("error").path("code").asInt());
                assertEquals("拒绝访问", result.path("error").path("message").asText());
            }
        }
    }

    @Test(timeout = 40000)
    public void officialBrowserReceivesLateStreamFailure() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 流中已收到的消息与结束时的错误均保留。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "broken");
                assertEquals(1, result.path("values").size());
                assertEquals("中文🙂", result.path("values").get(0).path("value").asText());
                assertEquals(9, result.path("error").path("code").asInt());
                assertEquals("late failure", result.path("error").path("message").asText());
            }
        }
    }

    @Test(timeout = 40000)
    public void browserAllowsCredentialsForConfiguredOrigin() throws Exception {
        // 浏览器先创建独立页面 origin，协议挂载仅允许该来源。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsCredentials", true) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext server = configuration.buildContext()) {
                // 容器启动后，按照用户用法发布接口并绑定 proto 文件。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 真实跨域凭证模式按精确 origin 通过预检并成功调用。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "credentials");
                assertEquals("中文🙂 <&>", result.path("value").asText());
                assertEquals(7, result.path("count").asInt());
                assertEquals(origin, result.path("origin").asText());
            }
        }
    }

    @Test(timeout = 40000)
    public void browserRejectsUnlistedOrigin() throws Exception {
        // 启动真实浏览器，制造跨域请求与严格配置之间的冲突。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", "http://127.0.0.1:1");
            AtomicInteger calls = new AtomicInteger();
            GrpcWebService implementation = new GrpcWebServiceImpl() {
                @Override
                public Message echo(Message request) {
                    calls.incrementAndGet();
                    return request;
                }
            };
            try (MetContext server = configuration.buildContext()) {
                // 计数仅观察实际业务入口，不绕过框架网络链路。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, implementation) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 未配置的来源被浏览器拒绝，业务不能被执行。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "denied");
                assertTrue(result.has("error"));
                assertNotEquals(0, result.path("error").path("code").asInt());
                assertEquals(0, calls.get());
                assertTrue(this.hasPreflight(result));
            }
        }
    }

    @Test(timeout = 40000)
    public void browserRejectsUnlistedRequestHeader() throws Exception {
        // 启动真实浏览器，制造跨域请求与严格配置之间的冲突。
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(5000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent");
            AtomicInteger calls = new AtomicInteger();
            GrpcWebService implementation = new GrpcWebServiceImpl() {
                @Override
                public Message echo(Message request) {
                    calls.incrementAndGet();
                    return request;
                }
            };
            try (MetContext server = configuration.buildContext()) {
                // 计数仅观察实际业务入口，不绕过框架网络链路。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, implementation) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 预检拒绝未允许的 custom-header，业务不能被执行。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "denied");
                assertTrue(result.has("error"));
                assertNotEquals(0, result.path("error").path("code").asInt());
                assertEquals(0, calls.get());
                assertTrue(this.hasPreflight(result));
            }
        }
    }

    @Test(timeout = 40000)
    public void http1BrowserCancellationLeavesServerBoundedByRequestDeadline() throws Exception {
        // HTTP/1 取消先停止浏览器读取，服务端按调用携带的 1500ms 期限回收无限流。
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebService implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    @Override
                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(request);
                        }
                    }

                    @Override
                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            }
        };
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true).option("corsOrigins", origin);
            try (MetContext server = configuration.buildContext()) {
                // 真实服务只通过 bootstrap 发布，浏览器使用官方 stream.cancel()。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, implementation) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 验证本地取消与最终期限回收；HTTP/1 半关闭不等同于立即取消远端订阅。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "cancel");
                assertTrue(result.path("cancelled").asBoolean());
                assertEquals(1, result.path("values").size());
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 40000)
    public void httpsHttp2BrowserCancellationReleasesServerSubscription() throws Exception {
        // 无限流只发送首条消息，用取消信号证明 HTTP/2 RST_STREAM 立即传播。
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebService implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean sent = new AtomicBoolean();

                    @Override
                    public void request(long count) {
                        if (count > 0 && this.sent.compareAndSet(false, true)) {
                            subscriber.onNext(request);
                        }
                    }

                    @Override
                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            }
        };
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start("--test-tls")) {
            // 浏览器仅跳过私有测试 CA 的信任检查；TLS 与 ALPN 均走实际传输。
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
            ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
            GrpcWebTlsUtils.serverTls(listener, 2, "NONE");
            listener.protocol("grpc-web").addressScheme("https").contextPath("/web") //
                    .option("streamBody", true).option("corsOrigins", origin);
            try (MetContext server = configuration.buildContext()) {
                // 发布后使用官方浏览器客户端调用，测试不向调用传递短期限。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, implementation) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 浏览器记录必须确认实际 HTTP/2，取消回收发生在 10s 默认期限之前。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("https://localhost:" + port + "/web", "h2cancel");
                assertTrue(result.path("cancelled").asBoolean());
                assertEquals(1, result.path("values").size());
                boolean http2 = false;
                for (JsonNode response : result.path("responses")) {
                    if (StringUtils.equals("h2", response.path("protocol").asText())) {
                        http2 = true;
                    }
                }
                assertTrue("Chromium must negotiate HTTP/2 through TLS ALPN", http2);
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 40000)
    public void browserDeadlineReleasesServerSubscription() throws Exception {
        // 服务流不产生结果，期限由官方客户端通过 grpc-timeout 传递。
        CountDownLatch cancelled = new CountDownLatch(1);
        GrpcWebService implementation = new GrpcWebServiceImpl() {
            @Override
            public Flow.Publisher<Message> list(Message request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        // 保留订阅直到调用期限到达。
                    }

                    @Override
                    public void cancel() {
                        cancelled.countDown();
                    }
                });
            }
        };
        try (GrpcWebBrowserProcess peer = GrpcWebBrowserProcess.start()) {
            String origin = peer.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("grpc-web").contextPath("/web") //
                    .option("streamBody", true).option("corsOrigins", origin);
            try (MetContext server = configuration.buildContext()) {
                // 启动后发布流服务，使用真实浏览器网络栈发起有期限的调用。
                server.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/proto/web.proto"));
                server.publisher().rsfService(GrpcWebService.class, implementation) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource(proto).register();

                // 标准 DeadlineExceeded 状态和服务订阅释放共同证明期限生效。
                int port = server.getListenAddress("grpc-web").getPort();
                JsonNode result = peer.invoke("http://127.0.0.1:" + port + "/web", "deadline");
                assertEquals(4, result.path("error").path("code").asInt());
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            }
        }
    }

    private boolean hasPreflight(JsonNode result) {
        for (JsonNode request : result.path("requests")) {
            if (StringUtils.equals("OPTIONS", request.path("method").asText())) {
                return true;
            }
        }
        return false;
    }
}
