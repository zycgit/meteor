/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Triple 的 Protobuf/gRPC 兼容面；使用标准 C/C++ gRPC SDK，不代表 Dubbo POJO 或 Hessian 能力。 */
@NxnRpc(name = "triple")
public class TripleGrpcNativeInteropTest {
    @TempDir
    public Path data;

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cGrpcCoreConsumerCallsMeteorTripleProvider() throws Exception {
        // 只启用 Triple provider，普通字符串接口绑定既有标准 StringValue 契约。
        MetConfiguration configuration = this.configuration(true);
        var contract = this.getClass().getResource("/protocol/proto/java-sdk-echo.proto");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .protocol("triple").serialize("Protobuf") //
                    .aliasName("triple", "meteor.java.sdk.TextEchoService") //
                    .bindResource("triple", new GrpcProtoResource(contract)).register();
            int port = provider.getListenAddress("triple").getPort();
            String target = "grpc://" + ContainerProcess.runnerHost() + ":" + port;

            // C Core peer 不知道 Meteor 的协议名称，以标准 gRPC 访问实际 Triple 监听。
            String message = "C gRPC Core → Meteor Triple 中文";
            List<String> command = List.of("/opt/meteor-peer/native/c/grpc/echo-peer", "consumer", message);
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("RESULT " + message), output);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTripleConsumerCallsCGrpcCoreProvider() throws Exception {
        // 独立 C Core server 复用原有单次 unary 入口，Meteor 明确选用 Triple 出站端点。
        MetConfiguration configuration = this.configuration(false);
        var contract = this.getClass().getResource("/protocol/proto/java-sdk-echo.proto");
        List<String> command = List.of("/opt/meteor-peer/native/c/grpc/echo-peer", "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("READY "));
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .protocol("triple").serialize("Protobuf") //
                    .aliasName("triple", "meteor.java.sdk.TextEchoService") //
                    .bindResource("triple", new GrpcProtoResource(contract)).register();
            InterAddress address = new InterAddress("triple", target.getHost(), target.getPort(), "default");
            TextEchoService remote = consumer.getRsfClient(address).getRemote(service);

            // 返回值由 C Core 和生成的 Protobuf-C codec 处理，不经过 Meteor gRPC provider。
            String expected = "Meteor Triple → C gRPC Core 中文";
            assertEquals(expected, remote.echo(expected));
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cppGrpcSdkConsumerSavesAndFindsOrderOnMeteorTriple() throws Exception {
        // C++ 复用现有官方生成的订单客户端，Meteor 只发布 Triple 的 Protobuf 绑定。
        MetConfiguration configuration = this.configuration(true);
        var contract = this.getClass().getResource("/protocol/proto/order.proto");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("triple").serialize("Protobuf") //
                    .aliasName("triple", "meteor.example.OrderDemoService") //
                    .bindResource("triple", new GrpcProtoResource(contract)).register();
            int port = provider.getListenAddress("triple").getPort();
            String target = "grpc://" + ContainerProcess.runnerHost() + ":" + port;

            // 基础保存查询保留既有 peer 的数据范围，不追加流式或注册发现组合。
            List<String> command = List.of("/opt/meteor-peer/native/cpp/grpc/order-peer", "consumer");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "meteor-order", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTripleConsumerSavesAndFindsOrderOnCppGrpcSdk() throws Exception {
        // 标准 C++ gRPC server 独立保存订单；Meteor 的订阅和客户端均选择 Triple。
        MetConfiguration configuration = this.configuration(false);
        var contract = this.getClass().getResource("/protocol/proto/order.proto");
        List<String> command = List.of("/opt/meteor-peer/native/cpp/grpc/order-peer", "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("Order provider is ready: "));
            consumer.start();
            MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol("triple").serialize("Protobuf") //
                    .aliasName("triple", "meteor.example.OrderDemoService") //
                    .bindResource("triple", new GrpcProtoResource(contract)).register();
            InterAddress address = new InterAddress("triple", target.getHost(), target.getPort(), "default");
            OrderDemoService orders = consumer.getRsfClient(address).getRemote(service);

            // SDK 保存的对象必须完整返回，证明真实双向契约及 Protobuf 编解码互通。
            Order expected = new Order();
            expected.setId(1001);
            expected.setDescription("Meteor Triple → C++ gRPC 中文");
            expected.setAttachment(new byte[] { 77, 101, 116, 101, 111, 114 });
            orders.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = orders.find(query);
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    private MetConfiguration configuration(boolean listen) throws Exception {
        // 根路径下显式启用 Triple 的 gRPC 模式；未安装 Meteor gRPC 协议挂载。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setRequestTimeout(10000);
        configuration.setDataHome(Files.createTempDirectory(this.data, "case-"));
        var connector = configuration.connector("http").option("httpVersion", 2);
        if (listen) {
            connector.bind("0.0.0.0", 0);
        }
        connector.protocol("triple").contextPath("/") //
                .option("httpMode", "grpc").option("serializeType", "Protobuf").option("streamBody", true);
        return configuration;
    }
}
