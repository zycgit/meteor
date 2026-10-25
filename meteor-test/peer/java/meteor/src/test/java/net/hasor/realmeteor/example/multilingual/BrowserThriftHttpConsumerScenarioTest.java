/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;

/** Apache Thrift 的官方 npm XHR 客户端在真实 Chromium 中消费 TJSON/HTTP 服务。 */
public class BrowserThriftHttpConsumerScenarioTest {
    @Test(timeout = 120000)
    public void officialThriftXhrReceivesTheSavedOrder() throws Exception {
        // 公开 bootstrap 只启用 Thrift HTTP/1，普通接口及 DTO 使用已有公共字段编号映射。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .option("httpVersion", 1).protocol("thrift-http") //
                .contextPath("/thrift").option("encoding", "json");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("thrift-http", "OrderThriftService").protocol("thrift-http").register();
            int port = provider.getListenAddress("thrift-http").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/thrift";

            // 浏览器进程使用官方生成 Client 和可配置请求头的 XHR API，直接调用 Meteor。
            Path script = OrderPeerProcess.sources().resolve("browser/thrift-consumer.cjs");
            List<String> command = List.of("node", script.toString());
            try (OrderPeerProcess browser = OrderPeerProcess.start("browser", command, target)) {
                JsonNode result = browser.awaitResult();
                OrderPeerProcess.assertOrder(result, "Order from browser Thrift", "TWV0ZW9y");
            }
        }
    }
}
