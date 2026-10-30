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

/** 发布 dubbo 订单服务。 */
public final class ProviderMain {
    private ProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置本地协议端点。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("tcp").bind("0.0.0.0", 8080).protocol("dubbo");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并发布订单服务。
            context.start();
            OrderDemoService implementation = new OrderDemoServiceImpl();
            context.publisher().rsfService(OrderDemoService.class, implementation) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("dubbo") //
                    .aliasName("dubbo", OrderDemoService.class.getName()) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("dubbo"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
