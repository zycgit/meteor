/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.services;
import net.hasor.cobble.StringUtils;

/** 固定库存数据，用于展示同一应用发布多个服务。 */
public class InventoryServiceImpl implements InventoryService {
    @Override
    public int availableStock(String sku) {
        return StringUtils.equals("COFFEE-001", sku) ? 42 : 0;
    }
}
