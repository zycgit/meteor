/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.jsonrpc;
import java.net.URI;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 jsonrpc4j 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Throwable {
        // 使用 SDK 的 HTTP 客户端直接访问 Meteor 的 JSON-RPC 挂载路径。
        URI endpoint = OrderPeerSettings.target("http://127.0.0.1:8080/jsonrpc");
        JsonRpcHttpClient client = new JsonRpcHttpClient(endpoint.toURL());

        // SDK 按 orders 别名调用普通 Java 订单方法。
        Order order = OrderExampleUtils.newOrder(1001, "jsonrpc-sdk");
        Order saved = client.invoke("orders.save", new Object[] { order }, Order.class);
        OrderQuery query = new OrderQuery();
        query.setId(order.getId());
        Order result = client.invoke("orders.find", new Object[] { query }, Order.class);
        OrderExampleUtils.print(saved, result);
    }
}
