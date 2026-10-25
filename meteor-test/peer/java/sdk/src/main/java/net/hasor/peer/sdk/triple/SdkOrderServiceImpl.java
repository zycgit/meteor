/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.triple;
import meteor.example.OrderDemoService;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderProtoUtils;
import net.hasor.example.OrderQuery;

/** 官方 Triple SDK 的消息入口，业务数据仍交给公共订单实现。 */
public final class SdkOrderServiceImpl implements OrderDemoService {
    private final OrderDemoServiceImpl orders = new OrderDemoServiceImpl();

    @Override
    public OrderContract.Order save(OrderContract.Order order) {
        // SDK 负责 Protobuf，普通订单实现负责保存和查询。
        return OrderProtoUtils.toProto(this.orders.save(OrderProtoUtils.fromProto(order)));
    }

    @Override
    public OrderContract.Order find(OrderContract.OrderQuery request) {
        OrderQuery query = new OrderQuery();
        query.setId(request.getId());
        return OrderProtoUtils.toProto(this.orders.find(query));
    }
}
