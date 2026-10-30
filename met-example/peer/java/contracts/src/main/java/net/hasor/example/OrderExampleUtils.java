/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 共享订单输入和进程退出处理；协议配置、发布与订阅保留在各自 Main 中。 */
public final class OrderExampleUtils {
    public static final String SERVICE_NAME = "meteor.example.OrderDemoService";

    private OrderExampleUtils() {
    }

    public static Order newOrder(int id, String protocol) {
        Order order = new Order();
        order.setId(id);
        order.setDescription("created by " + protocol);
        order.setAttachment("meteor-order".getBytes(StandardCharsets.UTF_8));
        return order;
    }

    public static void print(Order order) throws JsonProcessingException {
        System.out.println(new ObjectMapper().writeValueAsString(order));
    }

    public static void awaitShutdown() throws InterruptedException {
        new CountDownLatch(1).await();
    }

    /** 为入口持有的资源注册退出关闭动作，保持提供者运行。 */
    public static void awaitShutdown(AutoCloseable resource) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                resource.close();
            } catch (Exception failure) {
                failure.printStackTrace();
            }
        }, "order-provider-shutdown"));
        awaitShutdown();
    }
}
