/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.example.services.Order;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.annotation.SolonMain;

/** 消费者监听 28771，与提供者错开；完成订单与库存查询后关闭 Solon 并退出。 */
@SolonMain
public class ConsumerMain {
    public static void main(String[] args) {
        SolonApp app = Solon.start(ConsumerMain.class, args, boot -> {
            boot.cfg().loadAdd("classpath:solon-consumer.properties");
            boot.context().beanMake(OrderClient.class);
        });

        try {
            OrderClient client = app.context().getBean(OrderClient.class);
            Order order = client.getOrder("ORDER-1001");
            System.out.printf("订单 %s：商品 %s，数量 %d，金额 %s，状态 %s%n", // 展示远程返回的订单 DTO。
                    order.getOrderId(), order.getSku(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
            System.out.println("商品可用库存：" + client.availableStock(order.getSku()));
        } finally {
            Solon.stopBlock(false, 0);
        }
    }
}
