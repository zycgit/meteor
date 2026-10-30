/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hprose;
import hprose.client.HproseHttpClient;
import hprose.util.concurrent.Threads;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;

/** 使用 Hprose Java SDK 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 官方 SDK 创建 HTTP 代理，使用与 Meteor 发布配置一致的函数前缀。
        HproseHttpClient client = new HproseHttpClient("http://127.0.0.1:8080/hprose");
        try {
            OrderDemoService orders = client.useService(OrderDemoService.class, "orders");

            // 保存后按编号查询，结果保持为相同的普通 Java DTO。
            orders.save(OrderExampleUtils.newOrder(1001, "hprose-sdk"));
            OrderQuery query = new OrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(orders.find(query));
        } finally {
            client.close();
            Threads.runShutdownHandler();
        }
    }
}
