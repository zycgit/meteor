/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.services;
import java.math.BigDecimal;
import net.hasor.cobble.StringUtils;

/** 示例数据存放在内存中，不依赖数据库。实例由 Spring 容器管理。 */
public class OrderServiceImpl implements OrderService {
    @Override
    public Order getOrder(String orderId) {
        if (!StringUtils.equals("ORDER-1001", orderId)) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        Order order = new Order();
        order.setOrderId(orderId);
        order.setSku("COFFEE-001");
        order.setQuantity(2);
        order.setTotalAmount(new BigDecimal("59.80"));
        order.setStatus("PAID");
        return order;
    }
}
