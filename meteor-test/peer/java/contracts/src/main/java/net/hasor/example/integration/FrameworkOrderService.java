/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import net.hasor.example.OrderDemoService;
import net.hasor.meteor.annotation.MetService;

/** 框架矩阵独立持有的服务声明，业务模型归测试工程。 */
@MetService(name = "orders", group = "framework-nxn", version = "1")
public interface FrameworkOrderService extends OrderDemoService {
}
