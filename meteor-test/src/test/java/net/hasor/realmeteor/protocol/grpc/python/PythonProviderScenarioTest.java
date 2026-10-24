/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.python;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.example.order.OrderDemoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** Meteor 消费者通过公开代理调用独立 Python 官方 gRPC 服务。 */
public class PythonProviderScenarioTest {
    @Test(timeout = 30000)
    public void meteorConsumesPythonAsciiOrder() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonUnicodeOrder() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Meteor 订单 🙂 <&>");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonBinaryOrder() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment(new byte[] { 0, 1, 127, -128, -1 });
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonMinimumInt32() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(Integer.MIN_VALUE);
            expected.setDescription("Order from Meteor");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonMaximumInt32() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(Integer.MAX_VALUE);
            expected.setDescription("Order from Meteor");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonMissingOrderFailure() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("clear");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("clear"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "grpc";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 查询未保存的订单，错误必须来自远端业务而不是连接或等待失败。
            OrderQuery query = new OrderQuery();
            query.setId(404);
            var caller = client.getRsfClient(address);
            var pending = caller.asyncInvoke(service, "find", //
                    new Class<?>[] { OrderQuery.class }, new Object[] { query });
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> pending.get(5, TimeUnit.SECONDS));

            // 官方 Python SDK 的 INTERNAL 状态和业务消息由 Meteor 原样保留。
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(13, status.getCode());
            assertEquals("Order not found: 404", status.getDescription());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonOrderOverTls() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("tls");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("tls"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "https";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorConsumesPythonOrderOverMutualTls() throws Exception {
        // 配置客户端通道及公共订单契约，Python 单独创建官方 SDK server。
        MetConfiguration configuration = this.configuration("mtls");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
        try (PythonGrpcPeer peer = PythonGrpcPeer.provider("mtls"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("grpc", "meteor.example.OrderDemoService") //
                    .bindResource("grpc", resource).register();
            String scheme = "https";
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");

            // 保存并读取同一订单，核对直接参数和返回值的 Protobuf 映射。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order saved = remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 参数经过真实网络并进入 Python 存储，响应保留边界值及二进制内容。
            assertEquals(expected.getId(), saved.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    private MetConfiguration configuration(String security) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        var connector = configuration.connector("http").option("httpVersion", 2);
        var protocol = connector.protocol("grpc").contextPath("/");
        if (!StringUtils.equals("clear", security)) {
            connector.option("tlsEnabled", true) //
                    .option("tlsTrustStore", "classpath:/grpc/tls/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test") //
                    .option("tlsAlpn", "h2");
            protocol.addressScheme("https");
            if (StringUtils.equals("mtls", security)) {
                connector.option("tlsKeyStore", "classpath:/grpc/tls/acceptance/client.p12") //
                        .option("tlsKeyStorePassword", "meteor-test");
            }
        }
        return configuration;
    }
}
