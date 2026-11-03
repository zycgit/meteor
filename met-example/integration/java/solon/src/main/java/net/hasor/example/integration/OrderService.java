/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.annotation.MetService;

/** 复用 SDK 订单契约，通过注解声明框架自动装配所需的服务身份。 */
@MetService(name = OrderExampleUtils.SERVICE_NAME, group = "default", version = "1.0.0")
public interface OrderService extends OrderDemoService {
}
