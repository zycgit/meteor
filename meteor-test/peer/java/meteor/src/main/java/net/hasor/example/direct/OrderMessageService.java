/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import net.hasor.example.Order;

/** 消息只返回接收回执；订单业务的完成由应用自己确认。 */
public interface OrderMessageService {
    void accept(Order order) throws InterruptedException;
}
