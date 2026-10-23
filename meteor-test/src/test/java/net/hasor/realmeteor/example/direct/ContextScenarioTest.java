/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.hasor.example.direct.ContextMountExample;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.example.order.Order;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 使用可运行例子的相同配置和发布 API，展示服务如何选择已经配置的上下文。 */
public class ContextScenarioTest {
    @Test(timeout = 30000)
    public void omittedContextPublishesOnlyAtTheDefaultPath() throws Exception {
        // 服务端预配置三个 context，消费者选择默认的 /soap。
        MetConfiguration incoming = ContextMountExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = ContextMountExample.client("/soap");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            ContextMountExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // 不传 context 的一次发布可通过默认 SOAP 路径保存和查询订单。
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(binding);
            remote.save(this.order(401, "default context"));
            Order result = remote.find(this.query(401));
            assertEquals("default context", result.getDescription());

            // 默认路径有生成的 WSDL；额外 context 不会自动发布该服务。
            int port = server.getListenAddress("soap").getPort();
            HttpResponse<String> definition = this.definition(port, "/soap");
            assertEquals(200, definition.statusCode());
            assertTrue(definition.body(), definition.body().contains("/soap/orders"));
            assertEquals(404, this.definition(port, "/private").statusCode());
        }
    }

    @Test(timeout = 30000)
    public void explicitContextReplacesTheDefaultForThisService() throws Exception {
        // 发布时选择 /private，消费者的 SOAP 出口也选择同一路径。
        MetConfiguration incoming = ContextMountExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = ContextMountExample.client("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            ContextMountExample.publish(server, new OrderDemoServiceImpl(), "/private");
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // JSON-RPC 保存的数据，可从显式挂载的 SOAP 路径查询到同一个订单。
            OrderDemoService json = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(binding);
            json.save(this.order(402, "private context"));
            OrderDemoService soap = client.getRsfClient(server.getListenAddress("soap")).getRemote(binding);
            assertEquals("private context", soap.find(this.query(402)).getDescription());

            // SOAP 定义只跟随该服务选择的 context，默认路径不会继续暴露它。
            int port = server.getListenAddress("soap").getPort();
            HttpResponse<String> definition = this.definition(port, "/private");
            assertEquals(200, definition.statusCode());
            assertTrue(definition.body(), definition.body().contains("/private/orders"));
            assertEquals(404, this.definition(port, "/soap").statusCode());
        }
    }

    @Test(timeout = 30000)
    public void twoSelectedContextsExposeTheSameServiceInstance() throws Exception {
        // 同一监听提供两个已选择的挂载，每个消费者只配置自己访问的 SOAP context。
        MetConfiguration incoming = ContextMountExample.server("127.0.0.1", 0, 0);
        MetConfiguration privateOutgoing = ContextMountExample.client("/private");
        MetConfiguration partnerOutgoing = ContextMountExample.client("/partner");
        try (MetContext server = incoming.buildContext(); //
             MetContext privateClient = privateOutgoing.buildContext(); //
             MetContext partnerClient = partnerOutgoing.buildContext()) {
            server.start();
            privateClient.start();
            partnerClient.start();
            ContextMountExample.publish(server, new OrderDemoServiceImpl(), "/private", "/partner");
            MetBindInfo<OrderDemoService> privateBinding = DirectRpcExample.subscribe(privateClient);
            MetBindInfo<OrderDemoService> partnerBinding = DirectRpcExample.subscribe(partnerClient);

            // 从 private 写入后，partner 查询同一实现中的订单，服务没有重复注册。
            OrderDemoService privateRemote = privateClient.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(privateBinding);
            privateRemote.save(this.order(403, "shared context state"));
            OrderDemoService partnerRemote = partnerClient.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(partnerBinding);
            assertEquals("shared context state", partnerRemote.find(this.query(403)).getDescription());

            // 两个描述地址分别保留自己的挂载路径，未选择的默认路径仍不可见。
            int port = server.getListenAddress("soap").getPort();
            HttpResponse<String> privateDefinition = this.definition(port, "/private");
            HttpResponse<String> partnerDefinition = this.definition(port, "/partner");
            assertEquals(200, privateDefinition.statusCode());
            assertEquals(200, partnerDefinition.statusCode());
            assertTrue(privateDefinition.body(), privateDefinition.body().contains("/private/orders"));
            assertTrue(partnerDefinition.body(), partnerDefinition.body().contains("/partner/orders"));
            assertEquals(404, this.definition(port, "/soap").statusCode());
        }
    }

    private HttpResponse<String> definition(int port, String path) throws Exception {
        URI address = URI.create("http://127.0.0.1:" + port + path + "/orders?wsdl");
        HttpRequest request = HttpRequest.newBuilder(address) //
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpClient http = HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Order order(int id, String description) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(new byte[] { 1, 2, 3 });
        return order;
    }

    private OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }
}
