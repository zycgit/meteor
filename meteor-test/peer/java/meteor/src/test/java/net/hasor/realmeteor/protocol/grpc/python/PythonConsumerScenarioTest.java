/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.python;
import net.hasor.meteor.test.nxn.NxnRpc;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderDemoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Python 官方消费者通过生成的 SDK 调用 Meteor 订单服务。 */
@NxnRpc(name = "grpc")
public class PythonConsumerScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsAsciiOrder() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "ascii")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(42, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsUnicodeOrder() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "unicode")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(42, result.path("id").asInt());
                assertEquals("Python 订单 🙂 <&>", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsBinaryOrder() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "bytes")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(42, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("AAF/gP8=", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsMinimumInt32() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "int32-min")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(Integer.MIN_VALUE, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsMaximumInt32() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "int32-max")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(Integer.MAX_VALUE, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsMissingOrderFailure() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "clear", "failure")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的业务错误及其说明。
                assertEquals("INTERNAL", result.path("status").asText());
                assertTrue(result.path("message").asText().contains("Order not found: 404"));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsOrderOverTls() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("tls");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "tls", "ascii")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(42, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerReadsOrderOverMutualTls() throws Exception {
        // 只配置 Meteor 的公开服务入口，线上契约复用统一订单 proto。
        MetConfiguration configuration = this.configuration("mtls");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();

            // 真实 Python SDK 在独立进程中完成当前场景，不共享 Java 编解码器。
            int port = server.getListenAddress("grpc").getPort();
            try (PythonGrpcPeer peer = PythonGrpcPeer.consumer( //
                    ContainerProcess.runnerHost(), port, "mtls", "ascii")) {
                JsonNode result = peer.awaitResult();

                // 核对对端观察到的订单字段，Python 同时校验 save/find 的完整生成消息。
                assertEquals(42, result.path("id").asInt());
                assertEquals("Order from Python", result.path("description").asText());
                assertEquals("TWV0ZW9y", result.path("attachment").asText());
            }
        }
    }

    private MetConfiguration configuration(String security) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        var connector = configuration.connector("http").bind(ContainerProcess.runnerHost(), 0);
        var protocol = connector.protocol("grpc").contextPath("/");
        if (!StringUtils.equals("clear", security)) {
            connector.option("tlsEnabled", true) //
                    .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                    .option("tlsKeyStorePassword", "meteor-test") //
                    .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test") //
                    .option("tlsAlpn", "h2");
            protocol.addressScheme("https");
            if (StringUtils.equals("mtls", security)) {
                connector.option("tlsClientAuth", "REQUIRE");
            }
        }
        return configuration;
    }
}
