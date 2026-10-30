/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.jsonrpc;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 通过 JSON-RPC 发布订单服务。 */
public final class ProviderMain {
    private ProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置本地 HTTP 端口和协议。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.connector("http").bind("127.0.0.1", 8080) //
                .protocol("jsonrpc").contextPath("/jsonrpc");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并发布订单服务。
            context.start();
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("jsonrpc") //
                    .aliasName("jsonrpc", "orders") //
                    .register();
            System.out.println("Order provider is ready: " + context.getListenAddress("jsonrpc"));

            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
