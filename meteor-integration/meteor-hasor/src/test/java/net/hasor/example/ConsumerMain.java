/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.core.ApiBinder;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.core.Module;
import net.hasor.example.services.Order;
import net.hasor.meteor.MeteorModule;

/** 消费方监听 28761，与提供方端口错开；查询订单和库存后退出。 */
public class ConsumerMain implements Module {
    public static void main(String[] args) throws Throwable {
        Hasor hasor = Hasor.create()//
                .mainSettingWith("hasor-consumer.properties")//
                .addModules(new MeteorModule(), new ConsumerMain());

        try (AppContext app = hasor.build()) {
            OrderClient client = app.getInstance(OrderClient.class);
            Order order = client.getOrder("ORDER-1001");
            System.out.printf("订单 %s：商品 %s，数量 %d，金额 %s，状态 %s%n", order.getOrderId(), order.getSku(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
            System.out.println("商品可用库存：" + client.availableStock(order.getSku()));
        }
    }

    @Override
    public void loadModule(ApiBinder binder) {
        binder.bindType(OrderClient.class).asEagerSingleton();
    }
}
