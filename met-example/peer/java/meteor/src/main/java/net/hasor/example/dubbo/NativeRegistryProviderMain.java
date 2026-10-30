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
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;

/** 通过 Dubbo 原生 ZooKeeper 注册中心发布 Meteor 订单服务。 */
public final class NativeRegistryProviderMain {
    private NativeRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 使用 Dubbo 原生登记格式接入本地 ZooKeeper。
        DubboRegistryConfiguration registry = new DubboRegistryConfiguration() //
                .setAddress("zookeeper://127.0.0.1:2181");
        registry.setPublishHost("127.0.0.1");
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("meteor-native-dubbo-order-provider");
        configuration.addExtension(registry);
        configuration.connector("tcp").bind("0.0.0.0", 8080).protocol("dubbo");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器后发布接口，扩展自动向 ZooKeeper 登记实际业务端口。
            context.start();
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderDemoService.class.getName()) //
                    .group("default") //
                    .version("1.0.0") //
                    .protocol("dubbo") //
                    .aliasName("dubbo", OrderDemoService.class.getName()) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("dubbo"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
