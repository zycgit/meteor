/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.grpc;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderExampleUtils;

/** 两端使用相同的官方 gRPC 方法描述，路径与 Meteor 的 /grpc 挂载保持一致。 */
final class SdkOrderMethods {
    static final String SERVICE_NAME = "grpc/" + OrderExampleUtils.SERVICE_NAME;

    static final MethodDescriptor<OrderContract.Order, OrderContract.Order> SAVE =
            MethodDescriptor.<OrderContract.Order, OrderContract.Order>newBuilder() //
                    .setType(MethodDescriptor.MethodType.UNARY) //
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "save")) //
                    .setRequestMarshaller(ProtoUtils.marshaller(OrderContract.Order.getDefaultInstance())) //
                    .setResponseMarshaller(ProtoUtils.marshaller(OrderContract.Order.getDefaultInstance())) //
                    .build();

    static final MethodDescriptor<OrderContract.OrderQuery, OrderContract.Order> FIND =
            MethodDescriptor.<OrderContract.OrderQuery, OrderContract.Order>newBuilder() //
                    .setType(MethodDescriptor.MethodType.UNARY) //
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "find")) //
                    .setRequestMarshaller(ProtoUtils.marshaller(OrderContract.OrderQuery.getDefaultInstance())) //
                    .setResponseMarshaller(ProtoUtils.marshaller(OrderContract.Order.getDefaultInstance())) //
                    .build();

    private SdkOrderMethods() {
    }
}
