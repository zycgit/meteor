/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;

/** 框架发布同一份标准订单契约，框架身份由进程控制事件单独报告。 */
public class FrameworkOrderServiceImpl implements FrameworkOrderService {
    private final OrderDemoServiceImpl orders = new OrderDemoServiceImpl();

    @Override
    public Order save(Order order) {
        return this.orders.save(order);
    }

    @Override
    public Order find(OrderQuery query) {
        return this.orders.find(query);
    }
}
