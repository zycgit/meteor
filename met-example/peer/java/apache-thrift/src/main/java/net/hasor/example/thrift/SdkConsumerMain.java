/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.thrift;
import net.hasor.example.Order;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.thrift.sdk.generated.OrderThriftService;
import net.hasor.example.thrift.sdk.generated.ThriftOrderQuery;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

/** 使用 Apache Thrift SDK 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 使用 Binary 编码和 Framed 传输连接本地 Meteor 服务。
        try (TFramedTransport transport = new TFramedTransport(new TSocket("127.0.0.1", 8080))) {
            transport.open();
            OrderThriftService.Client orders = new OrderThriftService.Client(new TBinaryProtocol(transport));

            // 使用官方生成的 Client 保存并查询订单。
            Order order = OrderExampleUtils.newOrder(1001, "thrift-sdk");
            orders.save(OrderThriftSdkService.toSdk(order));
            ThriftOrderQuery query = new ThriftOrderQuery();
            query.setId(1001);
            OrderExampleUtils.print(OrderThriftSdkService.toOrder(orders.find(query)));
        }
    }
}
