/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.grpc.python;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅验证 Triple 的 Protobuf/gRPC 兼容面；对端为 grpcio，不是 Python Triple SDK。 */
public class TriplePythonGrpcInteropTest {
    @Test(timeout = 40000)
    public void pythonGrpcClientCallsMeteorTripleProtobuf() throws Exception {
        // Meteor 仅安装 Triple，显式选择 gRPC 传输和普通订单 Protobuf 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        var connector = configuration.connector("http").option("httpVersion", 2);
        connector.bind(ContainerProcess.runnerHost(), 0);
        connector.protocol("triple").contextPath("/").option("httpMode", "grpc").option("streamBody", true);
        var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("triple").aliasName("triple", "meteor.example.OrderDemoService") //
                    .serialize("Protobuf").bindResource("triple", resource).register();

            // 复用独立 grpcio 进程直接保存并查询，不经 gRPC 插件、代理或 Triple POJO 包装。
            int port = provider.getListenAddress("triple").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "ascii")) {
                JsonNode result = peer.awaitResult();
                assertEquals(42, result.required("id").intValue());
                assertEquals("Order from Python", result.required("description").textValue());
                assertEquals("TWV0ZW9y", result.required("attachment").textValue());
            }
        }
    }

    @Test(timeout = 40000)
    public void meteorTripleClientCallsPythonGrpcProtobuf() throws Exception {
        // grpcio 发布标准 Protobuf 服务；Meteor 仅配置 Triple 出站能力。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").option("httpVersion", 2) //
                .protocol("triple").contextPath("/").option("httpMode", "grpc").option("streamBody", true);
        var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            var service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol("triple").aliasName("triple", "meteor.example.OrderDemoService") //
                    .serialize("Protobuf").bindResource("triple", resource).register();
            var address = new InterAddress("triple", peer.host(), port, "default");
            OrderDemoService remote = consumer.getRsfClient(address).getRemote(service);

            // 保存与查询使用同一订单，验证真实 Python 业务状态及两个 RPC 返回值。
            Order request = new Order();
            request.setId(42);
            request.setDescription("Order from Meteor Triple");
            request.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            Order saved = remote.save(request);
            OrderQuery query = new OrderQuery();
            query.setId(request.getId());
            Order found = remote.find(query);
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }
}
