/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.integration;
import java.util.concurrent.CountDownLatch;
import net.hasor.meteor.EnableMeteor;
import net.hasor.meteor.MetContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

/** 先运行提供者，再运行 ConsumerMain；监听 28880，退出进程时由 Spring 关闭服务。 */
@EnableMeteor
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:spring-provider.properties")
public class ProviderMain {
    public static void main(String[] args) throws InterruptedException {
        try (ConfigurableApplicationContext app = SpringApplication.run(ProviderMain.class, args)) {
            MetContext meteor = app.getBean(MetContext.class);
            System.out.println("订单服务已启动：" + meteor.getListenAddress("rsf"));
            System.out.println("JSON-RPC 与 Hessian 共用 HTTP 端口，分别挂载 /jsonrpc、/hessian："
                    + meteor.getListenAddress("jsonrpc") + "，" + meteor.getListenAddress("hessian"));
            new CountDownLatch(1).await();
        }
    }

    @Bean
    public OrderServiceImpl orderService() {
        return new OrderServiceImpl();
    }
}
