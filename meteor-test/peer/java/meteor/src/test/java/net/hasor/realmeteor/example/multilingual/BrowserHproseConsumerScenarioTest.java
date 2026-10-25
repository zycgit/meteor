/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

/** 官方 Hprose HTML5 SDK 在真实浏览器中调用 Meteor 的基础订单场景。 */
public class BrowserHproseConsumerScenarioTest {
    @Test(timeout = 120000)
    public void html5ConsumerSavesAndFindsTheMeteorOrder() throws Exception {
        // 独立 Meteor 应用通过 bootstrap 启动并发布 Hprose 订单服务。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain("hprose", "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");
            Path script = OrderPeerProcess.sources().resolve("browser/hprose-consumer.cjs");
            List<String> command = List.of("node", script.toString());

            // Chromium 先导航到真实提供者建立来源，再加载官方 SDK，不拦截网络响应。
            try (OrderPeerProcess browser = OrderPeerProcess.start("browser", command, target)) {
                JsonNode result = browser.awaitResult();

                // 查询结果来自 Meteor 提供者，包含编号、描述和二进制附件。
                OrderPeerProcess.assertOrder(result, "Order from Hprose HTML5", "TWV0ZW9y");
            }
        }
    }
}
