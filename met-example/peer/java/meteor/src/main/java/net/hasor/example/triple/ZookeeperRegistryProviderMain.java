/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.triple;
import java.net.URL;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 通过 ZooKeeper 登记 triple 订单服务。 */
public final class ZookeeperRegistryProviderMain {
    private ZookeeperRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 ZooKeeper，配置业务监听。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("triple-order-provider");
        configuration.addExtension(new ZookeeperRegistryConfiguration().setAddress("127.0.0.1:2181") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http").bind("0.0.0.0", 8080).option("httpVersion", 2) //
                .protocol("triple").contextPath("/") //
                .option("serializeType", "Protobuf");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").bind("127.0.0.1", 8081).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动后发布订单服务，扩展自动登记服务地址。
            context.start();
            URL contract = ZookeeperRegistryProviderMain.class.getResource("/order.proto");
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("triple") //
                    .aliasName("triple", OrderExampleUtils.SERVICE_NAME) //
                    .bindResource("triple", new GrpcProtoResource(contract)) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("triple"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
