/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Chromium 的同源 Fetch API 直接调用 HTTP RPC，不使用 Node 的 HTTP 客户端代替浏览器。 */
@ParameterizedClass(autoCloseArguments = false, name = "browser/fetch/{0}/direct", quoteTextArguments = false)
@MethodSource("protocols")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "browser/fetch/jsonrpc/direct")
@NxnRpc(name = "connect", version = "1", cases = "browser/fetch/connect/direct")
@NxnRpc(name = "soap", version = "1.1", cases = "browser/fetch/soap/direct")
@NxnRpc(name = "xmlrpc", cases = "browser/fetch/xmlrpc/direct")
public class BrowserHttpConsumerScenarioTest {
    private final String protocol;

    public BrowserHttpConsumerScenarioTest(String protocol) {
        this.protocol = protocol;
    }
    public static List<Object[]> protocols() {
        // 固定顺序只用于矩阵展示，每一项生成独立的保存查询场景。
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { "jsonrpc" });
        protocols.add(new Object[] { "connect" });
        protocols.add(new Object[] { "soap" });
        protocols.add(new Object[] { "xmlrpc" });
        return protocols;
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void browserFetchSavesAndFindsTheOrder() throws Exception {
        // Meteor 提供者经 bootstrap 独立启动，只开放当前协议。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain(this.protocol, "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");
            Path script = OrderPeerProcess.sources().resolve("browser/http-consumer.cjs");
            List<String> command = List.of("node", script.toString(), this.protocol);

            // Chromium 先访问真实服务端点建立同源文档，保留默认网络安全设置且不拦截请求。
            try (OrderPeerProcess browser = OrderPeerProcess.start("browser", command, target)) {
                JsonNode result = browser.awaitResult();

                // 确认保存后从 Meteor 查询到普通字段和二进制附件。
                OrderPeerProcess.assertOrder(result, "Order from browser fetch", "TWV0ZW9y");
            }
        }
    }
}
