/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.soap;
import java.net.URI;
import javax.xml.namespace.QName;
import jakarta.xml.ws.Service;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 Eclipse Metro 读取 Meteor 的 WSDL，并调用订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 标准 JAX-WS 客户端从网络 WSDL 取得端点和 SOAP 契约。
        URI wsdl = OrderPeerSettings.target("http://127.0.0.1:8080/soap/orders?wsdl");
        QName serviceName = new QName(MetroOrderService.NAMESPACE, "orders");
        QName portName = new QName(MetroOrderService.NAMESPACE, "ordersPort");
        Service service = Service.create(wsdl.toURL(), serviceName);
        MetroOrderService orders = service.getPort(portName, MetroOrderService.class);

        // 使用普通订单业务内容完成保存和查询，结果统一打印为 JSON。
        Order order = OrderExampleUtils.newOrder(1001, "soap-sdk");
        Order saved = orders.save(MetroOrder.from(order)).toOrder();
        MetroOrderQuery query = new MetroOrderQuery();
        query.setId(order.getId());
        Order found = orders.find(query).toOrder();
        OrderExampleUtils.print(saved, found);
    }
}
