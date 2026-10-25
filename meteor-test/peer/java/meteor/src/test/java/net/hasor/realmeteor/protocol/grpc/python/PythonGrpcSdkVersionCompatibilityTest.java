/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.txt.
 */
package net.hasor.realmeteor.protocol.grpc.python;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** gRPC Python 1.x 取最新补丁，生成器和运行时使用同一版本。 */
@RunWith(Parameterized.class)
public class PythonGrpcSdkVersionCompatibilityTest {
    private final String version;
    private final String direction;

    public PythonGrpcSdkVersionCompatibilityTest(String version, String direction) {
        this.version = version;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/python/grpc/grpcio@{0}/protobuf/{1}")
    public static Object[][] versions() {
        return new Object[][] { { "1.85.0", "SDK-to-Meteor" }, { "1.85.0", "Meteor-to-SDK" } };
    }

    @Test(timeout = 60000)
    public void exchangesOrderUsingTheLatestSdkBranch() throws Exception {
        // 通道从公开 bootstrap 创建，当前发行版与对应生成器一起准备。
        boolean sdkConsumer = StringUtils.equals(this.direction, "SDK-to-Meteor");
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        var connector = configuration.connector("http").option("httpVersion", 2);
        if (sdkConsumer) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("grpc").contextPath("/");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));

        if (sdkConsumer) {
            // Python 官方消费者实际保存并查询 Meteor 状态，读取到的字段来自真实 HTTP/2 响应。
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .protocol("grpc").aliasName("grpc", "meteor.example.OrderDemoService") //
                        .serialize("Protobuf").bindResource("grpc", resource).register();
                int port = provider.getListenAddress("grpc").getPort();
                try (PythonGrpcPeer peer = PythonGrpcPeer.versionConsumer( //
                        this.version, ContainerProcess.runnerHost(), port)) {
                    JsonNode result = peer.awaitResult();
                    assertEquals(42, result.required("id").intValue());
                    assertEquals("Python 订单 🙂 <&>", result.required("description").textValue());
                    assertEquals("TWV0ZW9y", result.required("attachment").textValue());
                }
            }
            return;
        }

        // Python SDK 独立创建官方服务端；Meteor 不共享对端消息对象或序列化实现。
        try (PythonGrpcPeer peer = PythonGrpcPeer.versionProvider(this.version); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            var service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol("grpc").aliasName("grpc", "meteor.example.OrderDemoService") //
                    .serialize("Protobuf").bindResource("grpc", resource).register();
            InterAddress address = new InterAddress("grpc", peer.host(), port, "default");
            OrderDemoService remote = consumer.getRsfClient(address).getRemote(service);

            // 同一次订单往返包含 Unicode 和非文本附件，确认没有退化为字符串转换。
            Order expected = new Order();
            expected.setId(1042);
            expected.setDescription("Meteor SDK 订单 🙂 <&>");
            expected.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order actual = remote.find(query);
            assertEquals(expected.getId(), actual.getId());
            assertEquals(expected.getDescription(), actual.getDescription());
            assertArrayEquals(expected.getAttachment(), actual.getAttachment());
        }
    }
}
