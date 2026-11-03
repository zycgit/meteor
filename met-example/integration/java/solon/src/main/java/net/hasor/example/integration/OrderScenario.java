/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** 三个框架执行相同的远程保存、查询流程。 */
public final class OrderScenario {
    private OrderScenario() {
    }

    public static void run(MetContext context, OrderService orders) throws Exception {
        // 等待注册发现，不用业务请求重试掩盖调用失败。
        String bindingId = context.getServiceInfo(OrderService.class).getBindID();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(bindingId);
            if (addresses != null && !addresses.isEmpty()) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new TimeoutException("未发现订单服务，请检查提供者和注册中心配置：" + bindingId);
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        Order input = OrderExampleUtils.newOrder(1001, context.getApplicationName());
        orders.save(input);
        OrderQuery query = new OrderQuery();
        query.setId(input.getId());
        Order result = orders.find(query);
        if (result.getId() != input.getId() || !Arrays.equals(input.getAttachment(), result.getAttachment())) {
            throw new IllegalStateException("订单编号或附件与保存结果不一致");
        }
        System.out.println("调用成功：" + OrderService.class.getSimpleName());
        OrderExampleUtils.print(result);
    }
}
