/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.peer.sdk.dubbo;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用原 com.alibaba 命名空间的 Dubbo 2.6 SDK 调用相同订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 旧版没有当前的 DubboBootstrap，直接配置该进程拥有的远程引用。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.service.shutdown.wait", "1000");
        ApplicationConfig application = new ApplicationConfig("dubbo-classic-order-consumer");
        application.setQosEnable(false);
        ReferenceConfig<OrderDemoService> reference = new ReferenceConfig<>();
        reference.setApplication(application);
        reference.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        reference.setInterface(OrderDemoService.class);
        reference.setGroup("default");
        reference.setVersion("1.0.0");
        reference.setScope("remote");
        reference.setRetries(0);
        reference.setTimeout(10000);
        String path = "/" + OrderDemoService.class.getName() + "?serialization=hessian2";
        reference.setUrl(OrderPeerSettings.target("dubbo://127.0.0.1:8080" + path).toString());
        ProtocolConfig protocol = new ProtocolConfig("dubbo");
        try {
            // 两次调用由原 SDK 连接和编码，结果来自刚保存的订单。
            OrderDemoService orders = reference.get();
            Order order = OrderExampleUtils.newOrder(1001, "dubbo-sdk");
            Order saved = orders.save(order);
            OrderQuery query = new OrderQuery();
            query.setId(order.getId());
            OrderExampleUtils.print(saved, orders.find(query));
        } finally {
            // 独立进程关闭自己的引用及协议连接池，不借用当前 Apache SDK。
            reference.destroy();
            protocol.destroy();
        }
    }
}
