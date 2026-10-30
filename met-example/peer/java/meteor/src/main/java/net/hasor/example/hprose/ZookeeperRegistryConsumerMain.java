/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hprose;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.example.RegistryConnectionExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 从 ZooKeeper 发现 hprose 订单服务。 */
public final class ZookeeperRegistryConsumerMain {
    private ZookeeperRegistryConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 ZooKeeper，配置出站协议。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.setApplicationName("hprose-order-consumer");
        configuration.addExtension(new ZookeeperRegistryConfiguration().setAddress("127.0.0.1:2181") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http").protocol("hprose").contextPath("/hprose");
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 容器启动后订阅当前协议的订单服务。
            context.start();
            MetBindInfo<OrderDemoService> binding = context.publisher().rsfService(OrderDemoService.class) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("hprose") //
                    .aliasName("Hprose", "orders") // 对应 Hprose 编码器的函数名前缀。
                    .register();

            // 等待服务发现，再保存和查询订单。
            RegistryConnectionExample.awaitDiscovery(context, binding);
            OrderDemoService orders = context.getRsfClient().getRemote(binding);
            orders.save(OrderExampleUtils.newOrder(1001, "hprose"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        }
    }
}
