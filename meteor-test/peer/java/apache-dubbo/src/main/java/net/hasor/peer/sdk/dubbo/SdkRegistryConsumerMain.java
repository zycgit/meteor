/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.dubbo;
import java.util.Map;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 使用 Apache Dubbo SDK 从原生 ZooKeeper 注册中心发现并调用 Meteor 提供者。 */
public final class SdkRegistryConsumerMain {
    private SdkRegistryConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // SDK 只配置注册中心，引用中不设置提供者 URL 或业务端口。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        ApplicationConfig application = new ApplicationConfig("dubbo-sdk-registry-order-consumer");
        application.setQosEnable(false);
        application.setMetadataType("local");
        application.setRegisterMode("interface");
        RegistryConfig registry = new RegistryConfig("zookeeper://" + arguments[0]);
        registry.setRegisterMode("interface");
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);

        // 固定接口级发现，服务身份与 Meteor 原生注册示例保持一致。
        ReferenceConfig<OrderDemoService> reference = new ReferenceConfig<>();
        reference.setInterface(OrderDemoService.class);
        reference.setGroup(arguments.length > 1 ? arguments[1] : "default");
        reference.setVersion("1.0.0");
        reference.setParameters(Map.of("migration.step", "FORCE_INTERFACE", "serialization", "hessian2"));
        reference.setScope("remote");

        try {
            // 注册订阅完成后，通过自动发现的提供者保存和查询订单。
            bootstrap.reference(reference).start();
            OrderDemoService orders = reference.get();
            Order order = OrderExampleUtils.newOrder(1001, "dubbo-sdk-registry");
            Order saved = orders.save(order);
            OrderQuery query = new OrderQuery();
            query.setId(order.getId());
            OrderExampleUtils.print(saved, orders.find(query));
        } finally {
            bootstrap.destroy();
        }
    }
}
