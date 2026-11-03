/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.connect;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import me.ivovk.connect_rpc_java.core.http.Paths;
import me.ivovk.connect_rpc_java.netty.ConnectNettyServerBuilder;
import me.ivovk.connect_rpc_java.netty.server.NettyServer;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderProtoUtils;
import net.hasor.example.OrderQuery;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 社区 connect-rpc-java SDK 提供者，复用普通 Java 订单业务。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 使用 SDK 的标准 Protobuf marshaller 定义 save 和 find 两个方法。
        var orders = new OrderDemoServiceImpl();
        var orderMarshaller = ProtoUtils.marshaller(OrderContract.Order.getDefaultInstance());
        var queryMarshaller = ProtoUtils.marshaller(OrderContract.OrderQuery.getDefaultInstance());
        var save = MethodDescriptor.<OrderContract.Order, OrderContract.Order>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName(OrderExampleUtils.SERVICE_NAME + "/save") //
                .setRequestMarshaller(orderMarshaller).setResponseMarshaller(orderMarshaller).build();
        var find = MethodDescriptor.<OrderContract.OrderQuery, OrderContract.Order>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName(OrderExampleUtils.SERVICE_NAME + "/find") //
                .setRequestMarshaller(queryMarshaller).setResponseMarshaller(orderMarshaller).build();

        // SDK 方法只做契约与 DTO 转换，订单存储仍由共享业务实现负责。
        ServerServiceDefinition service = ServerServiceDefinition.builder(OrderExampleUtils.SERVICE_NAME) //
                .addMethod(save, ServerCalls.asyncUnaryCall((request, response) -> {
                    respond(response, () -> OrderProtoUtils.toProto(orders.save(OrderProtoUtils.fromProto(request))));
                })) //
                .addMethod(find, ServerCalls.asyncUnaryCall((request, response) -> {
                    OrderQuery query = new OrderQuery();
                    query.setId(request.getId());
                    respond(response, () -> OrderProtoUtils.toProto(orders.find(query)));
                })).build();

        // 社区 SDK 独立监听 /connect，当前入口负责自己的服务端及执行线程。
        ExecutorService workers = Executors.newCachedThreadPool();
        try (NettyServer server = ConnectNettyServerBuilder.forServices(service) //
                .host(OrderPeerSettings.bindHost()).port(OrderPeerSettings.port()) //
                .pathPrefix(Paths.Path.of("connect")) //
                .executor(workers).build()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                workers.shutdownNow();
            }, "connect-sdk-provider-shutdown"));
            String target = OrderPeerSettings.publishAddress("http", server.getPort(), "/connect").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            OrderExampleUtils.awaitShutdown();
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static <T> void respond(StreamObserver<T> response, Supplier<T> invocation) {
        try {
            response.onNext(invocation.get());
            response.onCompleted();
        } catch (Exception failure) {
            response.onError(Status.INTERNAL.withDescription(failure.getMessage()).withCause(failure).asException());
        }
    }
}
