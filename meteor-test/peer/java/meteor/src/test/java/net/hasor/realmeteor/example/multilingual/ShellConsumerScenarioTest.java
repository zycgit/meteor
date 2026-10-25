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

/** curl 入门脚本只需要一个 JSON-RPC 提供者，不启动其它业务协议。 */
public class ShellConsumerScenarioTest {
    @Test(timeout = 120000)
    public void curlScriptSavesAndFindsTheJsonRpcOrder() throws Exception {
        // 提供者只挂载 JSON-RPC，向独立 curl 进程报告实际网络地址。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain("jsonrpc", "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");
            Path script = OrderPeerProcess.sources().resolve("shell/jsonrpc/consumer.sh");
            List<String> command = List.of("bash", script.toString());

            // 运行真实 curl，最后一行必须是保存后查询的完整业务结果。
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "Order from curl", "TWV0ZW9y");
            }
        }
    }
}
