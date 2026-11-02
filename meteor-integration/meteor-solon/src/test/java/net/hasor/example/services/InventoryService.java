/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.services;
import net.hasor.meteor.annotation.MetService;

/** 库存查询契约，独立于订单服务发布。 */
@MetService(group = "solon-demo", version = "1.0")
public interface InventoryService {
    /** 查询商品的可用库存；未知商品返回 0。 */
    int availableStock(String sku);
}
