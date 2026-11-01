/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 Apache Thrift Node SDK 与 Meteor 的 Binary framed TCP 双向基础互通。 */
@NxnRpc(name = "thrift")
public class NodeThriftOrderScenarioTest {
    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerSavesAndFindsTheMeteorOrder() throws Exception {
        // Meteor 的独立应用从 bootstrap 启动，仅发布 Thrift 订单接口。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain("thrift", "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // Node 官方 SDK 在自己的容器进程中保存并查询订单，验证完整返回对象。
            try (OrderPeerProcess consumer = OrderPeerProcess.foreignConsumer("node", "thrift", target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "Order from Node.js", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerSavesAndFindsTheNodeOrder() throws Exception {
        // Node 由官方生成 Processor 发布服务，独立监听真实 TCP 临时端口。
        try (OrderPeerProcess provider = OrderPeerProcess.foreignProvider("node", "thrift")) {
            String target = provider.awaitReady("Order provider is ready: ");

            // Meteor 消费者通过公开应用入口建立代理，附件必须经过服务端保存再读取。
            try (OrderPeerProcess consumer = OrderPeerProcess.javaMain("thrift", "Consumer", target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "created by thrift", "bWV0ZW9yLW9yZGVy");
            }
        }
    }
}
