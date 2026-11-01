/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleUnaryService;
import net.hasor.example.protocol.triple.TripleUnaryServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** Triple 普通 HTTP unary 与 HTTP/2 gRPC 传输分开验收。 */
@NxnRpc(name = "triple")
public class TripleHttpScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void http1JsonUnaryPreservesUnicode() throws Exception {
        // HTTP 版本与 Triple 编码独立配置，不因发布协议增加监听端口。
        MetConfiguration incoming = this.configuration(true, 1, "json");
        MetConfiguration outgoing = this.configuration(false, 1, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅普通 Java unary 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Json").register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Json").register();
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // JSON 的请求正文为按位置排列的参数数组，返回值直接是业务 DTO。
            TripleMessage result = remote.echo(TripleMessage.of("HTTP Triple 中文", 8));
            assertEquals("HTTP Triple 中文", result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void http1ProtobufUnaryPreservesUnicode() throws Exception {
        // HTTP 版本与 Triple 编码独立配置，不因发布协议增加监听端口。
        MetConfiguration incoming = this.configuration(true, 1, "proto");
        MetConfiguration outgoing = this.configuration(false, 1, "proto");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅普通 Java unary 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // HTTP unary 使用裸 Protobuf 正文，不增加 gRPC 五字节消息头。
            TripleMessage result = remote.echo(TripleMessage.of("HTTP Protobuf 中文", 8));
            assertEquals("HTTP Protobuf 中文", result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void http2JsonUnaryPreservesUnicode() throws Exception {
        // HTTP 版本与 Triple 编码独立配置，不因发布协议增加监听端口。
        MetConfiguration incoming = this.configuration(true, 2, "json");
        MetConfiguration outgoing = this.configuration(false, 2, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅普通 Java unary 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Json").register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Json").register();
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // JSON 的请求正文为按位置排列的参数数组，返回值直接是业务 DTO。
            TripleMessage result = remote.echo(TripleMessage.of("HTTP Triple 中文", 8));
            assertEquals("HTTP Triple 中文", result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void http2ProtobufUnaryPreservesUnicode() throws Exception {
        // HTTP 版本与 Triple 编码独立配置，不因发布协议增加监听端口。
        MetConfiguration incoming = this.configuration(true, 2, "proto");
        MetConfiguration outgoing = this.configuration(false, 2, "proto");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅普通 Java unary 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // HTTP unary 使用裸 Protobuf 正文，不增加 gRPC 五字节消息头。
            TripleMessage result = remote.echo(TripleMessage.of("HTTP Protobuf 中文", 8));
            assertEquals("HTTP Protobuf 中文", result.getText());
            assertEquals(9, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void httpJsonMultipleArgumentsRemainPositional() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true, 1, "json");
        MetConfiguration outgoing = this.configuration(false, 1, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 第一个参数与第二个 primitive 参数不能交换位置。
            assertEquals("HTTPHTTPHTTP", remote.greet("HTTP", 3));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void httpJsonVoidHasAnEmptyBusinessReturn() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true, 1, "json");
        MetConfiguration outgoing = this.configuration(false, 1, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // void 方法仍等待一次普通 HTTP RPC 的成功终态。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] input = { TripleMessage.of("void", 2) };
            assertNull(caller.asyncInvoke(service, "accept", types, input).getData(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void httpJsonNullReturnRemainsNull() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true, 1, "json");
        MetConfiguration outgoing = this.configuration(false, 1, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // JSON null 是正常业务结果，不应被当成缺少响应正文。
            assertNull(remote.list(null));
        }
    }

    private MetConfiguration configuration(boolean listener, int version, String mode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", version);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("httpMode", mode);
        return configuration;
    }
}
