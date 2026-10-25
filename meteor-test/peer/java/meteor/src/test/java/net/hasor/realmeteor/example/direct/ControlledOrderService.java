/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;

/** 仅在故障场景中控制业务阶段，正常示例实现不携带锁闩和等待。 */
final class ControlledOrderService implements OrderDemoService {
    static final  int              FAILURE_ID  = -1;
    static final  int              BLOCKING_ID = -2;
    final         CountDownLatch   entered     = new CountDownLatch(1);
    final         CountDownLatch   release     = new CountDownLatch(1);
    final         CountDownLatch   finished    = new CountDownLatch(1);
    private final OrderDemoService orders      = new OrderDemoServiceImpl();

    ControlledOrderService() {
        Order order = new Order();
        order.setId(42);
        order.setDescription("ready");
        this.orders.save(order);
    }

    @Override
    public Order save(Order order) {
        return this.orders.save(order);
    }

    @Override
    public Order find(OrderQuery query) {
        // 特定输入只负责注入业务失败或等待，不改变 RPC 契约。
        if (query.getId() == FAILURE_ID) {
            throw new IllegalStateException("inventory unavailable");
        }
        if (query.getId() == BLOCKING_ID) {
            this.entered.countDown();
            try {
                if (!this.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Controlled order query was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Controlled order query was interrupted", e);
            } finally {
                this.finished.countDown();
            }
            OrderQuery released = new OrderQuery();
            released.setId(42);
            return this.orders.find(released);
        }
        return this.orders.find(query);
    }
}
