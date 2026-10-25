/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 只使用普通 Java 接口和 Meteor XML 注解，从启动入口发布并调用 SOAP 服务。 */
public class MetXmlExample {
    public static void main(String[] args) throws Exception {
        // 配置双端 SOAP 挂载，只有服务端打开监听。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布服务与订阅相同 Java 接口，无需绑定 WSDL。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, order -> order) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // DTO 的 XML 名称由注解给出；业务代码仍使用 Java 属性。
            MetXmlOrder order = new MetXmlOrder();
            order.setId("order-2026");
            order.setBuyer("Alice");
            order.setDelivery("Bob");
            order.setLines(List.of("Meteor", "SOAP"));
            order.setState(MetXmlState.READY);
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlOrder result = remote.echo(order);
            System.out.println(result.getId() + ": " + result.getBuyer() + " -> " + result.getDelivery());
        }
    }
}
