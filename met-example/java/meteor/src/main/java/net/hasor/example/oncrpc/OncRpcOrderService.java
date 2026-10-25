/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import net.hasor.example.Order;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** 程序号与过程号保持稳定；订单可按公共字段号自动映射，也可绑定 order.x 的指针契约。 */
@MetService(id = 0x31234569L)
public interface OncRpcOrderService {
    @MetMethod(id = 1)
    Order save(Order order);

    @MetMethod(id = 2)
    Order find(int id);
}
