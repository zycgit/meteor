/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import com.google.protobuf.ByteString;

/** 官方 SDK 示例共用的订单与 Protobuf 转换，不承担网络通信。 */
public final class OrderProtoUtils {
    private OrderProtoUtils() {
    }

    public static OrderContract.Order toProto(Order order) {
        OrderContract.Order.Builder result = OrderContract.Order.newBuilder().setId(order.getId());
        if (order.getDescription() != null) {
            result.setDescription(order.getDescription());
        }
        if (order.getAttachment() != null) {
            result.setAttachment(ByteString.copyFrom(order.getAttachment()));
        }
        return result.build();
    }

    public static Order fromProto(OrderContract.Order message) {
        Order order = new Order();
        order.setId(message.getId());
        order.setDescription(message.getDescription());
        order.setAttachment(message.getAttachment().toByteArray());
        return order;
    }
}
