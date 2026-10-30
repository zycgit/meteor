/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;

/** 发布 oncrpc 订单服务。 */
public final class ProviderMain {
    private ProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置本地协议端点。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("tcp").bind("127.0.0.1", 8080).protocol("oncrpc");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并发布订单服务。
            context.start();
            OncRpcServiceResource contract = new OncRpcServiceResource(OncRpcOrderService.class) //
                    .codec(Order.class, new OrderXdrCodec());
            OncRpcOrderBridge implementation = new OncRpcOrderBridge(new OrderDemoServiceImpl());
            context.publisher().rsfService(OncRpcOrderService.class, implementation) //
                    .name("meteor.example.idl.oncrpc.Order") //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", contract) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("oncrpc"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
