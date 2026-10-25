/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

/** Chromium 打开真实页面，通过容器网络调用独立的 Meteor 提供者。 */
public class BrowserConsumerScenarioTest {
    @Test(timeout = 150000)
    public void browserPageSavesAndFindsTheGrpcWebOrder() throws Exception {
        // 页面与 RPC 服务分别在常驻容器内使用独立进程和系统分配的端口。
        String pageServer = "/opt/meteor-peer/node/grpcweb/order-page/serve.mjs";
        List<String> command = List.of("node", pageServer);
        try (OrderPeerProcess page = OrderPeerProcess.start("browser", command, null); //
                OrderPeerProcess provider = OrderPeerProcess.javaMain("grpcweb", "Provider", null)) {
            String pageAddress = page.awaitReady("Browser order page is ready: ");
            String target = provider.awaitReady("Order provider is ready: ");

            // 按页面默认订单直接点击；业务调用由打包后的官方 gRPC-Web SDK 完成。
            List<String> browserCommand = List.of("node", "-e", this.browserActions(pageAddress));
            try (OrderPeerProcess browser = OrderPeerProcess.start("browser", browserCommand, target)) {
                JsonNode result = browser.awaitResult();

                // 页面显示保存后查询的订单，包含普通字段和 UTF-8 附件。
                OrderPeerProcess.assertOrder(result, "Order from browser", "TWV0ZW9y");
            }
        }
    }

    private String browserActions(String pageAddress) {
        return """
                const { chromium } = require('/opt/meteor-peer/node/grpcweb/node_modules/playwright');
                async function run() {
                    // 打开真实静态页面，保留 Chromium 的 CORS 检查。
                    const browser = await chromium.launch({ headless: true });
                    try {
                        const page = await browser.newPage();
                        const address = new URL('%s');
                        address.searchParams.set('target', process.env.METEOR_ORDER_TARGET);
                        await page.goto(address.toString());
                        await page.locator('button[type=submit]').click();

                        // 页面负责保存并查询；只读取用户最终可见的结果，不注入业务调用函数。
                        await page.waitForFunction(() => {
                            const text = document.querySelector('pre').textContent.trim();
                            return text.startsWith('{');
                        }, null, { timeout: 30000 });
                        const text = await page.locator('pre').textContent();
                        console.log(JSON.stringify(JSON.parse(text)));
                    } finally {
                        await browser.close();
                    }
                }
                run().catch(error => {
                    console.error(error.stack || error);
                    process.exitCode = 1;
                });
                """.formatted(pageAddress);
    }
}
