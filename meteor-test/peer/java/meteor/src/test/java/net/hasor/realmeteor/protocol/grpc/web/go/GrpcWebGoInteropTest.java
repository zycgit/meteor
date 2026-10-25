/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web.go;
import java.net.URI;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方 connect-go 使用 gRPC-Web 直接与 Meteor 双向互通，不经过协议代理。 */
public class GrpcWebGoInteropTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";

    @Test(timeout = 45000)
    public void goGrpcWebClientSavesAndFindsOrderOnMeteor() throws Exception {
        // Meteor 仅发布 gRPC-Web，复用订单契约，避免把 Connect 请求当作本协议证据。
        MetConfiguration configuration = this.configuration(true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("grpc-web").aliasName("grpc-web", SERVICE).serialize("Protobuf") //
                    .bindResource("grpc-web", resource).register();
            String target = "http://" + server.getListenAddress("grpc-web").getHostPort();

            // connect-go 显式选择 WithGRPCWeb，保存后再查询同一笔订单。
            try (GoPeerProcess peer = GoPeerProcess.start("grpc-web", "client", "plain", "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorGrpcWebClientSavesAndFindsOrderOnGo() throws Exception {
        // connect-go 的官方 Handler 自己监听临时端口，Meteor 只启用 gRPC-Web 出站能力。
        MetConfiguration configuration = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
        try (GoPeerProcess peer = GoPeerProcess.start("grpc-web", "server", "plain", "unicode", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("grpc-web").aliasName("grpc-web", SERVICE).serialize("Protobuf") //
                    .bindResource("grpc-web", resource).register();
            InterAddress address = new InterAddress("http", endpoint.getHost(), endpoint.getPort(), "default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);

            // 普通 Java 代理经真实网络保存和查询，核对编号、Unicode 和二进制附件。
            Order order = new Order();
            order.setId(101);
            order.setDescription("订单-你好-🌍-café");
            order.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            Order saved = remote.save(order);
            OrderQuery query = new OrderQuery();
            query.setId(order.getId());
            Order found = remote.find(query);
            assertEquals(order.getId(), saved.getId());
            assertEquals(order.getDescription(), saved.getDescription());
            assertArrayEquals(order.getAttachment(), saved.getAttachment());
            assertEquals(order.getId(), found.getId());
            assertEquals(order.getDescription(), found.getDescription());
            assertArrayEquals(order.getAttachment(), found.getAttachment());
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", 1);
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("grpc-web").addressScheme("http").contextPath("/");
        return configuration;
    }
}
