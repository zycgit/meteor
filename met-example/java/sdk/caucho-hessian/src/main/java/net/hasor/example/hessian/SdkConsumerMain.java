/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hessian;
import com.caucho.hessian.client.HessianProxyFactory;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;

/** 使用 Caucho Hessian SDK 调用 Meteor 发布的订单服务。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // Caucho 代理直接连接 Meteor 的 Hessian 服务地址。
        HessianProxyFactory factory = new HessianProxyFactory();
        factory.setHessian2Request(true);
        factory.setHessian2Reply(true);
        String endpoint = "http://127.0.0.1:8080/hessian/orders";
        OrderDemoService orders = (OrderDemoService) factory.create(OrderDemoService.class, endpoint);

        // 业务仍复用普通接口和 DTO，SDK 完成参数与返回值的二进制转换。
        orders.save(OrderExampleUtils.newOrder(1001, "hessian-sdk"));
        OrderQuery query = new OrderQuery();
        query.setId(1001);
        OrderExampleUtils.print(orders.find(query));
    }
}
