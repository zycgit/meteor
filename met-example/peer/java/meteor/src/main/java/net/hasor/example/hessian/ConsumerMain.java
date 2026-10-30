/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hessian;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 通过 Hessian 直连订单服务。 */
public final class ConsumerMain {
    private ConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置协议，消费者无需监听端口。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.connector("http").protocol("hessian").contextPath("/hessian");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并订阅订单服务。
            context.start();
            MetBindInfo<OrderDemoService> binding = context.publisher().rsfService(OrderDemoService.class) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("hessian") //
                    .aliasName("hessian", "orders") //
                    .register();

            // 直连本地服务，保存并查询订单。
            InterAddress address = new InterAddress("hessian", "127.0.0.1", 8080, "local");
            OrderDemoService orders = context.getRsfClient(address).getRemote(binding);
            orders.save(OrderExampleUtils.newOrder(1001, "hessian"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        }
    }
}
