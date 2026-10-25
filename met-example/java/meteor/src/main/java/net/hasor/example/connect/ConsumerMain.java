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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** 直接访问 connect 订单服务。 */
public final class ConsumerMain {
    private ConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置出站协议。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("http") //
                .protocol("connect").contextPath("/connect") //
                .option("serializeType", "Protobuf");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并订阅订单服务。
            context.start();
            URL contract = ConsumerMain.class.getResource("/order.proto");
            MetBindInfo<OrderDemoService> binding = context.publisher().rsfService(OrderDemoService.class) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("connect") //
                    .aliasName("connect", OrderExampleUtils.SERVICE_NAME) //
                    .bindResource("connect", new GrpcProtoResource(contract)) //
                    .register();

            // 连接本地提供者，保存并查询订单。
            InterAddress target = new InterAddress("connect", "127.0.0.1", 8080, "local");
            OrderDemoService orders = context.getRsfClient(target).getRemote(binding);
            orders.save(OrderExampleUtils.newOrder(1001, "connect"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        }
    }
}
