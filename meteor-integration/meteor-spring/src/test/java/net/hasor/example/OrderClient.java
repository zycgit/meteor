/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.Order;
import net.hasor.example.services.OrderService;
import org.springframework.beans.factory.annotation.Autowired;

/** 普通 Spring 业务 Bean：直接注入服务接口，不手动创建 RPC 代理。 */
public class OrderClient {
    @Autowired
    private OrderService     orderService;
    @Autowired
    private InventoryService inventoryService;

    public Order getOrder(String orderId) {
        return this.orderService.getOrder(orderId);
    }

    public int availableStock(String sku) {
        return this.inventoryService.availableStock(sku);
    }
}
