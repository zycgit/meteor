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

/** 业务逻辑复用 SDK 示例，适配带有 MetService 声明的接口。 */
public class OrderServiceImpl implements OrderService {
    private final OrderDemoServiceImpl delegate = new OrderDemoServiceImpl();

    @Override
    public Order save(Order order) {
        return this.delegate.save(order);
    }

    @Override
    public Order find(OrderQuery query) {
        return this.delegate.find(query);
    }
}
