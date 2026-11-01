/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 从公开发布入口和官方 Thrift 消息验证 HTTP 服务的路径可见性。 */
@NxnRpc(name = "thrift")
public class ThriftContextScenarioTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)) //
            .version(HttpClient.Version.HTTP_1_1).build();

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unspecifiedServiceContextUsesTheDefaultMount() throws Exception {
        // 服务没有选择上下文时，只通过协议的默认挂载调用。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift", "context 默认");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 官方生成客户端检查响应信封，并取得已执行的业务结果。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            assertEquals("context 默认", peer.recv_echo());
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unspecifiedServiceContextIsHiddenFromAdditionalMounts() throws Exception {
        // 协议已挂载附加目录，但服务没有选择该目录。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/private", "context 隔离");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 已挂载路径返回标准 Thrift 不可调用异常，不能穿透服务发布范围。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            TApplicationException failure = assertThrows(TApplicationException.class, peer::recv_echo);
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void explicitServiceContextExposesTheAdditionalMount() throws Exception {
        // 服务通过公开发布 API 选择附加目录。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/private").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/private", "context 显式");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 官方生成客户端检查响应信封，并取得已执行的业务结果。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            assertEquals("context 显式", peer.recv_echo());
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void explicitServiceContextReplacesTheDefaultSelection() throws Exception {
        // 显式选择附加目录后，服务不再自动暴露到默认目录。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/private").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift", "context 替换");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 已挂载路径返回标准 Thrift 不可调用异常，不能穿透服务发布范围。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            TApplicationException failure = assertThrows(TApplicationException.class, peer::recv_echo);
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nestedMountUsesTheLongestMatchingContext() throws Exception {
        // 父目录与嵌套目录同时挂载，服务只选择嵌套目录。
        MetConfiguration configuration = this.configuration("/thrift", "/thrift/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/thrift/private").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift/private?source=official", "context 最长");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 官方生成客户端检查响应信封，并取得已执行的业务结果。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            assertEquals("context 最长", peer.recv_echo());
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void longestMountDoesNotFallBackToAVisibleParentService() throws Exception {
        // 服务只选择父目录，请求进入较长目录后不能回退调用父目录服务。
        MetConfiguration configuration = this.configuration("/thrift", "/thrift/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/thrift").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift/private?source=official", "context 禁止回退");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 已挂载路径返回标准 Thrift 不可调用异常，不能穿透服务发布范围。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            TApplicationException failure = assertThrows(TApplicationException.class, peer::recv_echo);
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void prefixDoesNotMatchAnotherPathSegment() throws Exception {
        // 路径前缀匹配遵守目录边界，相邻的字符串前缀不代表挂载目录。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift-other", "context 目录边界");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 无匹配路由时由 HTTP 层拒绝，业务实现没有执行。
            assertEquals(404, response.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathIsCallableWithoutTheDefaultPrefix() throws Exception {
        // 精确发布路径直接作为完整端点，不追加默认上下文。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/echo").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/gateway/echo?source=official", "context 精确");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 官方生成客户端检查响应信封，并取得已执行的业务结果。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            assertEquals("context 精确", peer.recv_echo());
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathIsHiddenFromTheDefaultMount() throws Exception {
        // 服务选择精确路径后，默认挂载不能再调用该服务。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/echo").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift", "context 精确隔离");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 已挂载路径返回标准 Thrift 不可调用异常，不能穿透服务发布范围。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            TApplicationException failure = assertThrows(TApplicationException.class, peer::recv_echo);
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathDoesNotMatchChildPaths() throws Exception {
        // 精确端点不接受该路径的子目录。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/echo").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/gateway/echo/child", "context 精确子目录");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 无匹配路由时由 HTTP 层拒绝，业务实现没有执行。
            assertEquals(404, response.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathRetainsItsTrailingSlash() throws Exception {
        // 精确端点保留用户声明的尾部斜杠。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/echo/").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/gateway/echo/", "context 尾斜杠");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 官方生成客户端检查响应信封，并取得已执行的业务结果。
            assertEquals(200, response.statusCode());
            InteropService.Client peer = this.responseClient(response.body());
            assertEquals("context 尾斜杠", peer.recv_echo());
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathDoesNotDropItsTrailingSlash() throws Exception {
        // 去掉尾部斜杠的地址不能命中另一个精确端点。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/echo/").register();

            // 官方 Thrift 消息通过实际监听端口进入所选路径。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/gateway/echo", "context 尾斜杠隔离");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 无匹配路由时由 HTTP 层拒绝，业务实现没有执行。
            assertEquals(404, response.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void explicitMultipleContextsShareOneServiceAndOnePort() throws Exception {
        // 一个业务服务显式选择默认与附加上下文，两个入口共用 HTTP 监听。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger calls = new AtomicInteger();
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/thrift", "/private").register();

            // 两个请求使用同一端口和不同目录，均到达同一个业务对象。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest firstRequest = this.echoRequest(port, "/thrift", "default");
            HttpResponse<byte[]> first = this.http.send(firstRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, first.statusCode());
            assertEquals("default", this.responseClient(first.body()).recv_echo());
            HttpRequest secondRequest = this.echoRequest(port, "/private", "additional");
            HttpResponse<byte[]> second = this.http.send(secondRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, second.statusCode());
            assertEquals("additional", this.responseClient(second.body()).recv_echo());
            assertEquals(2, calls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactServicePathTakesPriorityOverTheContainingDirectory() throws Exception {
        // 相同 IDL 的两个独立服务分别选择父目录与完整精确路径。
        MetConfiguration configuration = this.configuration("/thrift", "/private");
        AtomicInteger directoryCalls = new AtomicInteger();
        AtomicInteger exactCalls = new AtomicInteger();
        ThriftDemoServiceImpl directory = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                directoryCalls.incrementAndGet();
                return "directory:" + value;
            }
        };
        ThriftDemoServiceImpl exact = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                exactCalls.incrementAndGet();
                return "exact:" + value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, directory).name("directory") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByPrefix("thrift-http", "/thrift").register();
            server.publisher().rsfService(ThriftDemoService.class, exact).name("exact") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/thrift/exact").register();

            // 精确路径必须优先路由至精确服务，不能与父目录服务混为歧义端点。
            int port = server.getListenAddress("thrift-http").getPort();
            HttpRequest request = this.echoRequest(port, "/thrift/exact", "selected");
            HttpResponse<byte[]> response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("exact:selected", this.responseClient(response.body()).recv_echo());
            assertEquals(1, exactCalls.get());
            assertEquals(0, directoryCalls.get());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void typedSubscriptionUsesTheExplicitExactPath() throws Exception {
        // 发布与订阅分别选择相同完整路径，客户端不应发送协议的默认目录。
        MetConfiguration incoming = this.configuration("/thrift", "/private");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("thrift-http");
        outgoing.connector("http").protocol("thrift-http").contextPath("/thrift");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/rpc").register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .contextPathByExact("thrift-http", "/gateway/rpc").register();

            // 公开类型客户端经真实网络访问声明的精确地址。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            assertEquals("exact proxy", caller.getRemote(service).echo("exact proxy"));
        }
    }

    private MetConfiguration configuration(String defaultPath, String additionalPath) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("thrift-http") //
                .contextPath(defaultPath).addContextPath(additionalPath);
        return configuration;
    }

    private HttpRequest echoRequest(int port, String path, String value) throws TException {
        // 报文仅由官方协议和生成的参数类型构造，不复用插件编解码器。
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol writer = new TBinaryProtocol(output);
        writer.writeMessageBegin(new TMessage("echo", TMessageType.CALL, 0));
        new InteropService.echo_args(value).write(writer);
        writer.writeMessageEnd();
        byte[] body = Arrays.copyOf(output.getArray(), output.length());
        URI address = URI.create("http://127.0.0.1:" + port + path);
        return HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/x-thrift") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private InteropService.Client responseClient(byte[] body) throws TException {
        TProtocol reader = new TBinaryProtocol(new TMemoryInputTransport(body));
        return new InteropService.Client(reader);
    }
}
