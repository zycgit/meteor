/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import net.hasor.meteor.EnableMeteor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

/** 消费者监听 28881，与提供者错开；保存和查询订单后关闭容器并退出。 */
@EnableMeteor
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:spring-consumer.properties")
public class ConsumerMain {
    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext app = SpringApplication.run(ConsumerMain.class, args)) {
            OrderClient client = app.getBean(OrderClient.class);
            client.run();
        }
    }

    @Bean
    public OrderClient orderClient() {
        return new OrderClient();
    }
}
