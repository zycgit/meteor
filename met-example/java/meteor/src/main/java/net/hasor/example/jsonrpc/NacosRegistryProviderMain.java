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
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 发布 jsonrpc 订单服务，并接入 Nacos。 */
public final class NacosRegistryProviderMain {
    private NacosRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 Nacos，配置业务监听。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.setApplicationName("jsonrpc-order-provider");
        configuration.addExtension(new NacosRegistryConfiguration().setAddress("127.0.0.1:8848") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http").bind("127.0.0.1", 8080).protocol("jsonrpc").contextPath("/jsonrpc");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").bind("127.0.0.1", 8081).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动后发布订单服务，扩展自动登记服务地址。
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
