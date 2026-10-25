/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;

/** 直接访问 oncrpc 订单服务。 */
public final class ConsumerMain {
    private ConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置出站协议。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("tcp").protocol("oncrpc");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并订阅订单服务。
            context.start();
            OncRpcServiceResource contract = new OncRpcServiceResource(OncRpcOrderService.class) //
                    .codec(Order.class, new OrderXdrCodec());
            MetBindInfo<OncRpcOrderService> binding = context.publisher().rsfService(OncRpcOrderService.class) //
                    .name("meteor.example.idl.oncrpc.Order") //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", contract) //
                    .register();

            // 连接本地提供者，保存并查询订单。
            InterAddress target = new InterAddress("oncrpc", "127.0.0.1", 8080, "local");
            OncRpcOrderService orders = context.getRsfClient(target).getRemote(binding);
            orders.save(OrderExampleUtils.newOrder(1001, "oncrpc"));
            OrderExampleUtils.print(orders.find(1001));
        }
    }
}
