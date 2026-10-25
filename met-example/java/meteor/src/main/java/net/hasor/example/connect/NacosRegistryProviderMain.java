/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.connect;
import java.net.URL;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 通过 Nacos 登记 connect 订单服务。 */
public final class NacosRegistryProviderMain {
    private NacosRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 Nacos，配置业务监听。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("connect-order-provider");
        configuration.addExtension(new NacosRegistryConfiguration().setAddress("127.0.0.1:8848") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http").bind("127.0.0.1", 8080) //
                .protocol("connect").contextPath("/connect") //
                .option("serializeType", "Protobuf");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").bind("127.0.0.1", 8081).protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动后发布订单服务，扩展自动登记服务地址。
            context.start();
            URL contract = NacosRegistryProviderMain.class.getResource("/order.proto");
            context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("connect") //
                    .aliasName("connect", OrderExampleUtils.SERVICE_NAME) //
                    .bindResource("connect", new GrpcProtoResource(contract)) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("connect"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
