/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.grpc;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderProtoUtils;
import net.hasor.example.OrderQuery;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用官方 grpc-java SDK 发布订单，供 Meteor 消费者调用。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 按公共契约注册两个方法，业务复用同一份订单实现。
        OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
        ServerServiceDefinition service = ServerServiceDefinition.builder(SdkOrderMethods.SERVICE_NAME) //
                .addMethod(SdkOrderMethods.SAVE, ServerCalls.asyncUnaryCall((request, response) -> {
                    response.onNext(OrderProtoUtils.toProto(orders.save(OrderProtoUtils.fromProto(request))));
                    response.onCompleted();
                })) //
                .addMethod(SdkOrderMethods.FIND, ServerCalls.asyncUnaryCall((request, response) -> {
                    OrderQuery query = new OrderQuery();
                    query.setId(request.getId());
                    response.onNext(OrderProtoUtils.toProto(orders.find(query)));
                    response.onCompleted();
                })) //
                .build();

        // HTTP/2 监听和 Protobuf 编解码均由官方 SDK 完成。
        Server server = NettyServerBuilder.forAddress(OrderPeerSettings.bindAddress()) //
                .addService(service) //
                .build().start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow, "grpc-sdk-provider-shutdown"));
        try {
            String target = OrderPeerSettings.publishAddress("http", server.getPort(), "/grpc").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            server.awaitTermination();
        } finally {
            server.shutdownNow();
        }
    }
}
