/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectHttpScenarioTest {
    @Test(timeout = 15000)
    public void jsonUnaryPreservesUnicodeBusinessValues() throws Exception {
        // 从公开配置入口挂载 Connect，客户端不开放监听端口。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动完成后再发布与订阅业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 普通 Java 代理发送一次 RPC，编码不影响最终业务类型。
            ConnectMessage result = remote.echo(ConnectMessage.of("你好 Connect 🌏", 6));
            assertEquals("你好 Connect 🌏", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufUnaryPreservesUnicodeBusinessValues() throws Exception {
        // 从公开配置入口挂载 Connect，客户端不开放监听端口。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动完成后再发布与订阅业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf") //
                    .bindResource(resource).register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 普通 Java 代理发送一次 RPC，编码不影响最终业务类型。
            ConnectMessage result = remote.echo(ConnectMessage.of("你好 Connect 🌏", 6));
            assertEquals("你好 Connect 🌏", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void businessFailureCompletesTheCallerFuture() throws Exception {
        // 从公开配置入口挂载 Connect，客户端不开放监听端口。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动完成后再发布与订阅业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 异步公开入口保留服务端错误，不能把业务失败吞成正常结果。
            MetClient caller = client.getRsfClient(server.getListenAddress("connect"));
            Class<?>[] types = { ConnectMessage.class };
            Object[] values = { ConnectMessage.of("failure", 1) };
            MetFuture pending = caller.asyncInvoke(service, "fail", types, values);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause().getMessage().contains("connect failure"));
        }
    }

    @Test(timeout = 20000)
    public void concurrentHttp2CallsKeepTheirOwnResults() throws Exception {
        // 从公开配置入口挂载 Connect，客户端不开放监听端口。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动完成后再发布与订阅业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 同时提交所有请求，使 HTTP/2 必须隔离并行调用结果。
            MetClient caller = client.getRsfClient(server.getListenAddress("connect"));
            List<MetFuture> pending = new ArrayList<>();
            Class<?>[] types = { ConnectMessage.class };
            for (int index = 0; index < 24; index++) {
                Object[] arguments = { ConnectMessage.of("call-" + index, index) };
                pending.add(caller.asyncInvoke(service, "echo", types, arguments));
            }

            // 每个 Future 对应自己的消息，没有跨 HTTP stream 串包。
            for (int index = 0; index < pending.size(); index++) {
                ConnectMessage result = (ConnectMessage) pending.get(index).getData(5, TimeUnit.SECONDS);
                assertEquals("call-" + index, result.getText());
                assertEquals(index + 1, result.getNumber());
            }
        }
    }

    @Test(timeout = 15000)
    public void jsonSideEffectFreeMethodCanUseGet() throws Exception {
        // GET 开关只作用于实际调用的方法，安全级别来自绑定的标准 proto。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("connect");
        outgoing.connector("http").option("httpVersion", 2).protocol("connect") //
                .contextPath("/rpc").option("get", true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").bindResource(resource).register();

            // Unicode 与 URI 保留字符经过 GET 查询参数编码后仍保留原值。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            String text = "GET 中文 + % / ? & =";
            ConnectMessage result = remote.safeEcho(ConnectMessage.of(text, 8));
            assertEquals(text, result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void protobufSideEffectFreeMethodCanUseGet() throws Exception {
        // GET 开关只作用于实际调用的方法，安全级别来自绑定的标准 proto。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("connect");
        outgoing.connector("http").option("httpVersion", 2).protocol("connect") //
                .contextPath("/rpc").option("get", true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf").bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf").bindResource(resource).register();

            // Unicode 与 URI 保留字符经过 GET 查询参数编码后仍保留原值。
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);
            String text = "GET 中文 + % / ? & =";
            ConnectMessage result = remote.safeEcho(ConnectMessage.of(text, 8));
            assertEquals(text, result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test(timeout = 15000)
    public void getRejectsAMethodWithoutSideEffectFreeDeclaration() throws Exception {
        // 同一个接口包含安全与普通方法，GET 不能被用于未声明安全的 echo。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("connect");
        outgoing.connector("http").option("httpVersion", 2).protocol("connect") //
                .contextPath("/rpc").option("get", true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            ConnectDemoServiceImpl implementation = new ConnectDemoServiceImpl();
            server.publisher().rsfService(ConnectDemoService.class, implementation) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf").bindResource(resource).register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Protobuf").bindResource(resource).register();

            // 校验在编码阶段失败，服务端的业务方法不得执行。
            MetClient caller = client.getRsfClient(server.getListenAddress("connect"));
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("not safe", 1) };
            MetFuture pending = caller.asyncInvoke(service, "echo", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause().getMessage().contains("NO_SIDE_EFFECTS"));
            assertEquals(0, implementation.getCalls());
        }
    }

    @Test(timeout = 15000)
    public void protobufPublicationRequiresAnExplicitProtoResource() throws Exception {
        // 协议校验通过已有发布事件执行，不等首次网络请求才发现缺失描述。
        MetConfiguration incoming = this.configuration(true);
        try (MetContext server = incoming.buildContext()) {
            server.start();
            var publication = server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Protobuf");

            // 只有 Protobuf 服务需要绑定 proto，错误由公开发布入口反馈。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, publication::register);
            assertTrue(failure.getMessage().contains("proto"));
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("connect").contextPath("/rpc").option("streamBody", true);
        return configuration;
    }
}
