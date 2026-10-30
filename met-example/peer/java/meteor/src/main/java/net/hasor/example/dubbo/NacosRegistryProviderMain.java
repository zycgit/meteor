/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.dubbo;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 通过 Nacos 登记 dubbo 订单服务。 */
public final class NacosRegistryProviderMain {
    private NacosRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 Nacos，配置业务监听。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("dubbo-order-provider");
        configuration.addExtension(new NacosRegistryConfiguration().setAddress("127.0.0.1:8848") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("tcp").bind("0.0.0.0", 8080).protocol("dubbo");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").bind("127.0.0.1", 8081).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动后发布订单服务，扩展自动登记服务地址。
            context.start();
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("dubbo") //
                    .aliasName("dubbo", OrderDemoService.class.getName()) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("dubbo"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
