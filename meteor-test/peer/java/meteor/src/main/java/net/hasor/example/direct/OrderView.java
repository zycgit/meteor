/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import net.hasor.example.Order;
import net.hasor.example.OrderQuery;

/** 只开放查询方法的兼容客户端视图。 */
public interface OrderView {
    Order find(OrderQuery query);
}
