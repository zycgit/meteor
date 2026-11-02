/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.example.services.Order;
import net.hasor.meteor.EnableMeteor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** 消费者监听 28781，与提供者错开；查询订单和库存后关闭容器并退出。 */
@EnableMeteor
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:spring-consumer.properties")
public class ConsumerMain {
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext(ConsumerMain.class)) {
            OrderClient client = app.getBean(OrderClient.class);
            Order order = client.getOrder("ORDER-1001");
            System.out.printf("订单 %s：商品 %s，数量 %d，金额 %s，状态 %s%n", // 展示远程返回的订单 DTO。
                    order.getOrderId(), order.getSku(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
            System.out.println("商品可用库存：" + client.availableStock(order.getSku()));
        }
    }

    @Bean
    public OrderClient orderClient() {
        return new OrderClient();
    }
}
