/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.triple;
import meteor.example.OrderDemoService;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderProtoUtils;
import net.hasor.example.OrderExampleUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 使用 Apache Dubbo SDK 调用 Meteor 发布的 triple 订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // SDK 只建立远程引用，不监听端口或通过本地服务完成调用。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        ApplicationConfig application = new ApplicationConfig("triple-sdk-order-consumer");
        application.setQosEnable(false);
        application.setMetadataType("local");
        RegistryConfig registry = new RegistryConfig(RegistryConfig.NO_AVAILABLE);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);
        ReferenceConfig<OrderDemoService> reference = new ReferenceConfig<>();
        reference.setGroup("default");
        reference.setVersion("1.0.0");
        reference.setInterface(OrderDemoService.class);
        String target = "tri://127.0.0.1:8080/" + OrderExampleUtils.SERVICE_NAME;
        reference.setUrl(target);
        reference.setScope("remote");

        try {
            // 使用同一订单输入保存，再按编号查询，并打印查询结果。
            bootstrap.reference(reference).start();
            OrderDemoService orders = reference.get();
            orders.save(OrderProtoUtils.toProto(OrderExampleUtils.newOrder(1001, "triple-sdk")));
            OrderContract.OrderQuery query = OrderContract.OrderQuery.newBuilder().setId(1001).build();
            OrderExampleUtils.print(OrderProtoUtils.fromProto(orders.find(query)));
        } finally {
            bootstrap.destroy();
        }
    }
}
