/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.direct.ProtocolDescriptionExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 同一订单示例分别使用自动契约与已有文件，不复制业务 DTO 或实现。 */
public class OrderContractMappingScenarioTest {
    @Test(timeout = 20000)
    public void fileConsumerCallsTheAnnotationOrderProvider() throws Exception {
        // 提供者由公共注解生成契约，消费者通过示例入口绑定已有 proto。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> service = ProtocolDescriptionExample.subscribeOrdersFromFile(client, "grpc");

            // 使用订单示例本身的数据和代理，一次保存验证两种契约入口的互通。
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            Order order = new Order();
            order.setId(71);
            order.setDescription("annotation provider");
            Order result = remote.save(order);
            assertEquals(71, result.getId());
            assertEquals("annotation provider", result.getDescription());
        }
    }

    @Test(timeout = 20000)
    public void annotationConsumerCallsTheFileOrderProvider() throws Exception {
        // 提供者以已有文件决定线上契约，消费者只依赖普通 Java 接口与公共注解。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            ProtocolDescriptionExample.publishOrdersFromFile(server, new OrderDemoServiceImpl(), "grpc");
            var service = ProtocolDescriptionExample.subscribeOrdersByAnnotations(client, "grpc");

            // 相同 DTO 在两端进入公共描述，协议各自保存已准备的 Protobuf 绑定。
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            Order order = new Order();
            order.setId(72);
            order.setDescription("file provider");
            Order result = remote.save(order);
            assertEquals(72, result.getId());
            assertEquals("file provider", result.getDescription());
        }
    }
}
