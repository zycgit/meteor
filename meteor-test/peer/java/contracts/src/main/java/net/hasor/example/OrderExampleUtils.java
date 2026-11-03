/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 共享订单输入和进程退出处理；协议配置、发布与订阅保留在各自 Main 中。 */
public final class OrderExampleUtils {
    public static final String SERVICE_NAME = "meteor.example.OrderDemoService";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderExampleUtils() {
    }

    public static Order newOrder(int id, String protocol) {
        String requestFile = System.getenv("METEOR_NXN_REQUEST_FILE");
        if (requestFile != null) {
            try {
                return MAPPER.readValue(new File(requestFile), Order.class);
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to read NXN order request: " + requestFile, failure);
            }
        }
        Order order = new Order();
        order.setId(id);
        order.setDescription("created by " + protocol);
        order.setAttachment("meteor-order".getBytes(StandardCharsets.UTF_8));
        return order;
    }

    public static void print(Order order) throws JsonProcessingException {
        if (System.getenv("METEOR_NXN_REQUEST_FILE") != null) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("event", "result");
            result.set("order", MAPPER.valueToTree(order));
            System.out.println(MAPPER.writeValueAsString(result));
        } else {
            System.out.println(MAPPER.writeValueAsString(order));
        }
    }

    /** 同时报告两次 RPC 的真实返回值；旧入口仍只输出查询结果。 */
    public static void print(Order saved, Order order) throws JsonProcessingException {
        if (System.getenv("METEOR_NXN_REQUEST_FILE") == null) {
            print(order);
            return;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("event", "result");
        result.set("saved", MAPPER.valueToTree(saved));
        result.set("order", MAPPER.valueToTree(order));
        System.out.println(MAPPER.writeValueAsString(result));
    }

    public static void ready(String target, String legacyPrefix) throws JsonProcessingException {
        if (System.getenv("METEOR_NXN_REQUEST_FILE") != null) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("event", "ready");
            result.put("target", target);
            System.out.println(MAPPER.writeValueAsString(result));
        } else {
            System.out.println(legacyPrefix + target);
        }
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
