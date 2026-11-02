/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.services;
import net.hasor.meteor.annotation.MetService;

/** 订单查询契约；提供者与消费者共享接口和 DTO。 */
@MetService(group = "hasor-demo", version = "1.0")
public interface OrderService {
    /** 查询示例订单，订单不存在时抛出业务异常。 */
    Order getOrder(String orderId);
}
