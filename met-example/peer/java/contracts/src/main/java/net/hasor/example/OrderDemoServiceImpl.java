/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 各协议示例复用的内存订单服务，保存和返回时复制数据以隔离调用方。 */
public final class OrderDemoServiceImpl implements OrderDemoService {
    private final Map<Integer, Order> orders = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        Order stored = copy(order);
        this.orders.put(stored.getId(), stored);
        return copy(stored);
    }

    @Override
    public Order find(OrderQuery query) {
        Order order = this.orders.get(query.getId());
        if (order == null) {
            throw new IllegalArgumentException("Order not found: " + query.getId());
        }
        return copy(order);
    }

    private static Order copy(Order source) {
        Order result = new Order();
        result.setId(source.getId());
        result.setDescription(source.getDescription());
        byte[] attachment = source.getAttachment();
        if (attachment != null) {
            result.setAttachment(Arrays.copyOf(attachment, attachment.length));
        }
        return result;
    }
}
