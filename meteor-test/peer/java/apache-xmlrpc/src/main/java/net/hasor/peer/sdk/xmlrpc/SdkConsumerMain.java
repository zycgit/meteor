/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.xmlrpc;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;

/** 使用 Apache XML-RPC 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 标准 SDK 访问 Meteor 的 XML-RPC 路径，关闭 Apache 私有类型扩展。
        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        config.setServerURL(OrderPeerSettings.target("http://127.0.0.1:8080/xmlrpc").toURL());
        config.setEnabledForExtensions(false);
        XmlRpcClient client = new XmlRpcClient();
        client.setConfig(config);

        // struct 显式保留 byte[]，由 SDK 将附件编码为 XML-RPC base64。
        Order order = OrderExampleUtils.newOrder(1001, "xmlrpc-sdk");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", order.getId());
        value.put("description", order.getDescription());
        value.put("attachment", order.getAttachment());
        client.execute("orders.save", new Object[] { value });

        // 查询结果恢复为共享订单模型，输出与 Meteor 消费者相同的 JSON。
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("id", 1001);
        Object result = client.execute("orders.find", new Object[] { query });
        OrderExampleUtils.print(new ObjectMapper().convertValue(result, Order.class));
    }
}
