/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.thrift;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;
import net.hasor.peer.sdk.thrift.generated.OrderThriftService;
import net.hasor.peer.sdk.thrift.generated.ThriftOrder;
import net.hasor.peer.sdk.thrift.generated.ThriftOrderQuery;

/** 将官方生成的 Thrift 契约接到共用订单业务，协议编解码由 Apache Thrift 处理。 */
final class OrderThriftSdkService implements OrderThriftService.Iface {
    private final OrderDemoService orders;

    OrderThriftSdkService(OrderDemoService orders) {
        this.orders = orders;
    }

    @Override
    public ThriftOrder save(ThriftOrder order) {
        return toSdk(this.orders.save(toOrder(order)));
    }

    @Override
    public ThriftOrder find(ThriftOrderQuery query) {
        OrderQuery request = new OrderQuery();
        request.setId(query.getId());
        return toSdk(this.orders.find(request));
    }

    static ThriftOrder toSdk(Order order) {
        ThriftOrder result = new ThriftOrder();
        result.setId(order.getId());
        result.setDescription(order.getDescription());
        result.setAttachment(order.getAttachment());
        return result;
    }

    static Order toOrder(ThriftOrder order) {
        Order result = new Order();
        result.setId(order.getId());
        result.setDescription(order.getDescription());
        result.setAttachment(order.getAttachment());
        return result;
    }
}
