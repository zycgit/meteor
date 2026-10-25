/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.grpcweb;
import java.net.URL;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** 发布 grpc-web 订单服务。 */
public final class ProviderMain {
    private ProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置本地协议端点。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("http").bind("127.0.0.1", 8080) //
                .protocol("grpc-web").contextPath("/grpc-web") //
                .option("serializeType", "Protobuf") //
                .option("corsOrigins", "http://127.0.0.1:8090,http://localhost:8090");

        try (MetContext context = configuration.buildContext()) {
            // 启动容器并发布订单服务。
            context.start();
            URL contract = ProviderMain.class.getResource("/order.proto");
            OrderDemoService implementation = new OrderDemoServiceImpl();
            context.publisher().rsfService(OrderDemoService.class, implementation) //
                    .name(OrderExampleUtils.SERVICE_NAME) //
                    .protocol("grpc-web") //
                    .aliasName("grpc-web", OrderExampleUtils.SERVICE_NAME) //
                    .bindResource("grpc-web", new GrpcProtoResource(contract)) //
                    .register();

            System.out.println("Order provider is ready: " + context.getListenAddress("grpc-web"));
            OrderExampleUtils.awaitShutdown(context);
        }
    }
}
