/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;

/** 专门验证参数和结果的 null 编码，不改变正常订单业务的输入约束。 */
final class NullableOrderService implements OrderDemoService {
    @Override
    public Order save(Order order) {
        return order;
    }

    @Override
    public Order find(OrderQuery query) {
        return null;
    }
}
