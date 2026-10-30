/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Connect Node 的两个 SDK 世代分别使用自己的依赖和生成契约，通过真实 HTTP/1.1 双向调用订单。 */
@RunWith(Parameterized.class)
public class NodeConnectSdkVersionScenarioTest {
    private final String profile;
    private final String direction;

    public NodeConnectSdkVersionScenarioTest(String sdk, String version, String profile, String direction) {
        this.profile = profile;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/node/connect/{0}@{1}/{2}/{3}")
    public static List<Object[]> versions() throws Exception {
        // 实际 SDK 版本由容器准备阶段核验，每个版本与调用方向形成独立用例。
        return NodeRpcPeer.compatibilityCases("connect");
    }

    @Test(timeout = 60000)
    public void orderRoundTripUsesTheSelectedSdkVersion() throws Exception {
        // 每个参数实例只验证一个方向，不修改其它用例共用的 SDK 路径或系统属性。
        if (StringUtils.equals(this.direction, "SDK-to-Meteor")) {
            this.nodeConsumerSavesAndFindsMeteorOrder();
        } else {
            this.meteorConsumerSavesAndFindsNodeOrder();
        }
    }

    private void nodeConsumerSavesAndFindsMeteorOrder() throws Exception {
        // 从公开 bootstrap 启动 HTTP/1.1，并在当前挂载点发布测试自有的订单契约。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0).option("httpVersion", 1) //
                .protocol("connect").contextPath("/versions/connect");
        var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("connect").aliasName("connect", OrderExampleUtils.SERVICE_NAME) //
                    .serialize("Protobuf").bindResource("connect", resource).register();
            int port = provider.getListenAddress("connect").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/versions/connect";

            // 对端使用指定版本的官方客户端保存再查询，结果必须保留完整订单字段。
            try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "consumer", target)) {
                JsonNode result = peer.requireResult();
                assertEquals(1001, result.required("id").intValue());
                assertEquals("Order from Node.js", result.required("description").textValue());
                assertEquals("TWV0ZW9y", result.required("attachment").textValue());
            }
        }
    }

    private void meteorConsumerSavesAndFindsNodeOrder() throws Exception {
        // SDK 提供者独立监听动态端口；Meteor 只准备同一路径的 Connect 出站通道。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").option("httpVersion", 1) //
                .protocol("connect").contextPath("/versions/connect");
        var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "provider"); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            var service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol("connect").aliasName("connect", OrderExampleUtils.SERVICE_NAME) //
                    .serialize("Protobuf").bindResource("connect", resource).register();
            var address = new InterAddress("connect", peer.host(), port, "default");
            OrderDemoService remote = consumer.getRsfClient(address).getRemote(service);

            // 保存响应和后续查询共同证明独立 SDK 的业务存储与 Protobuf 字段没有丢失。
            Order expected = OrderExampleUtils.newOrder(1001, "connect");
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getDescription(), saved.getDescription());
            assertArrayEquals(expected.getAttachment(), saved.getAttachment());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }
}
