/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 未发布版本的纯 C HTTP 客户端使用固定上游源码，验证一个 JSON-RPC 2.0 业务请求。 */
@NxnRpc(name = "jsonrpc", version = "2.0")
public class CJsonRpcSourceScenarioTest {
    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sourceCJsonRpcConsumerCallsMeteorProvider() throws Exception {
        // Meteor 从 bootstrap 发布普通文本接口，HTTP 地址对应同一条 JSON-RPC 挂载。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("0.0.0.0", 0).option("httpVersion", 1) //
                .protocol("jsonrpc").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .protocol("jsonrpc").aliasName("jsonrpc", "demo").register();
            int port = provider.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";

            // 上游原样 CLI 自己封装协议并进行网络收发，测试只提供方法名和业务参数。
            String message = "C source SDK 中文";
            ObjectMapper json = new ObjectMapper();
            String parameters = json.writeValueAsString(List.of(message));
            List<String> command = List.of("/opt/meteor-peer/native/c/jsonrpc/client", //
                    "-a", target, "-m", "demo.echo", "-p", parameters, "-i", "42");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, null)) {
                String output = consumer.awaitOutput();

                // 原程序同时打印 request 和 response；只从响应正文断言，不能误认请求日志。
                String marker = "response:\n";
                int responseOffset = output.indexOf(marker);
                assertTrue(responseOffset >= 0, output);
                String response = output.substring(responseOffset + marker.length());
                int bodyOffset = response.indexOf("\r\n\r\n");
                assertTrue(bodyOffset >= 0, response);
                JsonNode result = json.readTree(response.substring(bodyOffset + 4));
                assertEquals("2.0", result.required("jsonrpc").textValue());
                assertEquals(42, result.required("id").intValue());
                assertEquals(message, result.required("result").textValue());
            }
        }
    }
}
