/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hessian;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 发布 hessian 订单服务，并接入 ZooKeeper。 */
public final class ZookeeperRegistryProviderMain {
    private ZookeeperRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 ZooKeeper，配置业务监听。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.setApplicationName("hessian-order-provider");
        configuration.addExtension(new ZookeeperRegistryConfiguration().setAddress("127.0.0.1:2181") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http").bind("127.0.0.1", 8080).protocol("hessian").contextPath("/hessian");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").bind("127.0.0.1", 8081).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动后发布订单服务，扩展自动登记服务地址。
            context.start();
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("hessian") //
                    .aliasName("hessian", "orders") //
                    .register();
            System.out.println("Order provider is ready: " + context.getListenAddress("hessian"));

            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
