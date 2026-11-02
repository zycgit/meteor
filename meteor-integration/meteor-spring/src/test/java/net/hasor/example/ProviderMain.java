/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import java.util.concurrent.CountDownLatch;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.InventoryServiceImpl;
import net.hasor.example.services.OrderService;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.EnableMeteor;
import net.hasor.meteor.MetContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** 先运行提供者，再运行 ConsumerMain；监听 28780，退出进程时由 Spring 关闭服务。 */
@EnableMeteor
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:spring-provider.properties")
public class ProviderMain {
    public static void main(String[] args) throws InterruptedException {
        try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext(ProviderMain.class)) {
            app.registerShutdownHook();
            MetContext meteor = app.getBean(MetContext.class);
            System.out.println("订单与库存服务已启动：" + meteor.getListenAddress("rsf"));
            new CountDownLatch(1).await();
        }
    }

    @Bean
    public OrderService orderService() {
        return new OrderServiceImpl();
    }

    @Bean
    public InventoryService inventoryService() {
        return new InventoryServiceImpl();
    }
}
