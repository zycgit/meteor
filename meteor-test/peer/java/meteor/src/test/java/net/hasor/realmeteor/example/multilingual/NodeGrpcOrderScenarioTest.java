/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 Connect Node 的 gRPC 与 gRPC-Web 基础双向互通，各场景只启用当前线协议。 */
@NxnRpc(name = "grpc", cases = "node/grpc/direct")
@NxnRpc(name = "grpc-web", cases = "node/grpc-web/direct")
@ParameterizedClass(autoCloseArguments = false, name = "node/{0}/direct", quoteTextArguments = false)
@MethodSource("protocols")
public class NodeGrpcOrderScenarioTest {
    private final String protocol;

    public NodeGrpcOrderScenarioTest(String protocol) {
        this.protocol = protocol;
    }
    public static List<Object[]> protocols() {
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { "grpc" });
        protocols.add(new Object[] { "grpc-web" });
        return protocols;
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerSavesAndFindsTheMeteorOrder() throws Exception {
        // Meteor 的独立应用从 bootstrap 启动，仅发布所选协议的订单接口。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain(this.protocol, "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");
            Path script = Path.of("/opt/meteor-peer/node/connect/grpc-order.mjs");
            List<String> command = List.of("node", script.toString(), this.protocol, "consumer");

            // Node SDK 独立进程通过容器网络完成保存和查询。
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "Order from Node.js", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerSavesAndFindsTheNodeOrder() throws Exception {
        // Node SDK 直接发布 gRPC 或 gRPC-Web，不经过 Envoy 或协议转换代理。
        Path script = Path.of("/opt/meteor-peer/node/connect/grpc-order.mjs");
        List<String> command = List.of("node", script.toString(), this.protocol, "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("node", command, null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // Meteor 的独立消费应用经公开入口建立代理，验证服务端保存的完整订单。
            try (OrderPeerProcess consumer = OrderPeerProcess.javaMain(this.protocol, "Consumer", target)) {
                JsonNode result = consumer.awaitResult();
                String description = "created by " + this.protocol;
                OrderPeerProcess.assertOrder(result, description, "bWV0ZW9yLW9yZGVy");
            }
        }
    }
}
