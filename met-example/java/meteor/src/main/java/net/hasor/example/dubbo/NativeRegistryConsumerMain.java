/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.dubbo;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.example.RegistryConnectionExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;

/** 从 Dubbo 原生 ZooKeeper 注册中心发现 SDK 提供者，并通过 Meteor 调用。 */
public final class NativeRegistryConsumerMain {
    private NativeRegistryConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 只配置注册中心，业务地址由 Dubbo 原生发现提供。
        DubboRegistryConfiguration registry = new DubboRegistryConfiguration() //
                .setAddress("zookeeper://127.0.0.1:2181");
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("meteor-native-dubbo-order-consumer");
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("dubbo");

        try (MetContext context = configuration.buildContext()) {
            // 启动后登记消费者，原生注册扩展订阅同接口、分组和版本的提供者。
            context.start();
            MetBindInfo<OrderDemoService> binding = context.publisher().rsfService(OrderDemoService.class) //
                    .name(OrderDemoService.class.getName()) //
                    .group("default") //
                    .version("1.0.0") //
                    .protocol("dubbo") //
                    .aliasName("dubbo", OrderDemoService.class.getName()) //
                    .register();
            DubboRegistryClient nativeRegistryClient = context.getExtension(DubboRegistryClient.class);
            OrderDemoService orders = nativeRegistryClient.reference(binding);

            // 等待订阅结果，再通过自动发现的地址调用。
            RegistryConnectionExample.awaitDiscovery(context, binding);

            // 保存订单后按编号查询，输出和直连示例相同的 JSON 结构。
            orders.save(OrderExampleUtils.newOrder(1001, "dubbo-registry"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        }
    }
}
