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
            ClientCalls.blockingUnaryCall(channel, SdkOrderMethods.SAVE, options, order);

            // 查询刚保存的订单并输出公共 DTO 的 JSON。
            OrderContract.OrderQuery query = OrderContract.OrderQuery.newBuilder().setId(1001).build();
            OrderContract.Order result = ClientCalls //
                    .blockingUnaryCall(channel, SdkOrderMethods.FIND, options, query);
            OrderExampleUtils.print(OrderProtoUtils.fromProto(result));
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
