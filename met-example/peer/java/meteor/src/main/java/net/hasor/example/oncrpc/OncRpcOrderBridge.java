/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;
import net.hasor.example.Order;

/** 将 ONC RPC 的 IDL 签名转换为共享订单服务的 Java 签名。 */
public final class OncRpcOrderBridge implements OncRpcOrderService {
    private final OrderDemoService service;

    public OncRpcOrderBridge(OrderDemoService service) {
        this.service = service;
    }

    @Override
    public Order save(Order order) {
        return this.service.save(order);
    }

    @Override
    public Order find(int id) {
        // 查询条件由桥接层转换，订单存取仍交给同一份业务服务。
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return this.service.find(query);
    }
}
