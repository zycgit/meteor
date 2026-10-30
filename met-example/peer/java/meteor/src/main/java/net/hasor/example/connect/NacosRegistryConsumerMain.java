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
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.example.RegistryConnectionExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 从 Nacos 发现 connect 订单服务。 */
public final class NacosRegistryConsumerMain {
    private NacosRegistryConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 接入本地 Nacos，配置出站协议。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setApplicationName("connect-order-consumer");
        configuration.addExtension(new NacosRegistryConfiguration().setAddress("127.0.0.1:8848") //
                .setPublishHost("127.0.0.1"));
        configuration.connector("http") //
                .protocol("connect").contextPath("/connect") //
                .option("serializeType", "Protobuf");

        // RSF 端点供注册中心管理调用使用。
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 先启动消费容器，再订阅当前协议的订单接口。
            context.start();
            URL contract = NacosRegistryConsumerMain.class.getResource("/order.proto");
            MetBindInfo<OrderDemoService> binding = context.publisher().rsfService(OrderDemoService.class) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("connect") //
                    .aliasName("connect", OrderExampleUtils.SERVICE_NAME) //
                    .bindResource("connect", new GrpcProtoResource(contract)) //
                    .register();

            // 等待服务发现，再保存和查询订单。
            RegistryConnectionExample.awaitDiscovery(context, binding);
            OrderDemoService orders = context.getRsfClient().getRemote(binding);
            orders.save(OrderExampleUtils.newOrder(1001, "connect"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        }
    }
}
