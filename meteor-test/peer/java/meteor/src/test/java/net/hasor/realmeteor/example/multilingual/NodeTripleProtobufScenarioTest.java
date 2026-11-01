/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 只验证 Triple 的 Protobuf/gRPC 兼容面，不代表专用 Triple SDK 或 POJO 包装互通。 */
@NxnRpc(name = "triple")
public class NodeTripleProtobufScenarioTest {
    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeGrpcConsumerCallsMeteorTripleProtobuf() throws Exception {
        // 测试应用只挂载 triple，使用公开 bootstrap 并绑定订单 proto 资源。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain("triple", "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");
            List<String> command = List.of( //
                    "node", "/opt/meteor-peer/node/connect/grpc-order.mjs", "grpc", "consumer", "/");

            // Node 的标准 gRPC 客户端直接请求 Triple 根挂载，不经过代理或协议转换。
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, target)) {
                JsonNode result = consumer.awaitResult();

                // 完整订单由 Triple 提供者保存并查询，包含普通字段和 Protobuf bytes。
                OrderPeerProcess.assertOrder(result, "Order from Node.js", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTripleProtobufConsumerCallsNodeGrpc() throws Exception {
        // 官方 Connect Node 仅发布标准 gRPC 服务，双方使用同一份订单契约。
        List<String> command = List.of( //
                "node", "/opt/meteor-peer/node/connect/grpc-order.mjs", "grpc", "provider", "/");
        try (OrderPeerProcess provider = OrderPeerProcess.start("node", command, null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // Meteor 应用仅启用 triple 消费协议并绑定 proto，不换成 grpc 协议发起调用。
            try (OrderPeerProcess consumer = OrderPeerProcess.javaMain("triple", "Consumer", target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "created by triple", "bWV0ZW9yLW9yZGVy");
            }
        }
    }
}
