/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.meteor.annotation.MetParam;

/** 一份普通 Java 接口通过多个协议读写同一份订单数据。 */
public interface OrderDemoService {
    Order save(@MetParam(id = 1) Order order);

    Order find(@MetParam(id = 1) OrderQuery query);
}
