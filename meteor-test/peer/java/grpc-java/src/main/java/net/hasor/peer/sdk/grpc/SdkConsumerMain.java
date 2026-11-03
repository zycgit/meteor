/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.grpc;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import net.hasor.example.Order;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderProtoUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用官方 grpc-java SDK 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 创建官方 SDK 的 HTTP/2 连接，按挂载后的方法路径访问 Meteor。
        URI target = OrderPeerSettings.target("http://127.0.0.1:8080/grpc");
        ManagedChannel channel = NettyChannelBuilder.forAddress(target.getHost(), target.getPort()) //
                .usePlaintext().build();
        try {
            OrderContract.Order order = OrderProtoUtils.toProto(OrderExampleUtils.newOrder(1001, "grpc-sdk"));
            CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS);
            // 服务端返回的路径是部署目录，标准 gRPC 服务可以直接挂在根路径。
            String context = target.getPath();
            if (context == null) {
                context = "";
            }
            while (context.startsWith("/")) {
                context = context.substring(1);
            }
            while (context.endsWith("/")) {
                context = context.substring(0, context.length() - 1);
            }
            String service = OrderExampleUtils.SERVICE_NAME;
            if (!context.isEmpty()) {
                service = context + "/" + service;
            }
            var save = SdkOrderMethods.SAVE.toBuilder().setFullMethodName(service + "/save").build();
            var find = SdkOrderMethods.FIND.toBuilder().setFullMethodName(service + "/find").build();
            Order saved = OrderProtoUtils.fromProto(ClientCalls.blockingUnaryCall(channel, save, options, order));

            // 查询刚保存的订单并输出公共 DTO 的 JSON。
            OrderContract.OrderQuery query = OrderContract.OrderQuery.newBuilder().setId(order.getId()).build();
            OrderContract.Order result = ClientCalls //
                    .blockingUnaryCall(channel, find, options, query);
            Order found = OrderProtoUtils.fromProto(result);
            OrderExampleUtils.print(saved, found);
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
