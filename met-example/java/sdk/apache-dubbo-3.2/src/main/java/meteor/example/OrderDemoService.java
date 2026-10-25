/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package meteor.example;
import net.hasor.example.OrderContract;

/** Dubbo 3.2 SDK 按 proto 服务全名发布，接口名与 meteor.example.OrderDemoService 保持一致。 */
public interface OrderDemoService {
    OrderContract.Order save(OrderContract.Order order);

    OrderContract.Order find(OrderContract.OrderQuery query);
}
