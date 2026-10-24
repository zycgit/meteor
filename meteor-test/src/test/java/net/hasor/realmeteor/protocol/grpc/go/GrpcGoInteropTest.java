/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.go;
import java.lang.reflect.UndeclaredThrowableException;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** grpc-go 与 Meteor 双向直连，通过普通订单接口验证三种传输安全模式。 */
@RunWith(Parameterized.class)
public class GrpcGoInteropTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";
    private final        String tlsMode;

    public GrpcGoInteropTest(String tlsMode) {
        this.tlsMode = tlsMode;
    }

    @Parameterized.Parameters(name = "tls={0}")
    public static Object[][] tlsModes() {
        return new Object[][] { { "plain" }, { "tls" }, { "mtls" } };
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsUnicodeOrder() throws Exception {
        // Meteor 通过公开入口发布普通订单服务，绑定示例自带的 Protobuf 契约。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // grpc-go 客户端经真实网络保存并查询中文、补充平面字符和二进制字段。
            String target = server.getListenAddress("grpc").getHostPort();
            try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsMaximumIdOrder() throws Exception {
        // Meteor 通过公开入口发布普通订单服务，绑定示例自带的 Protobuf 契约。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // grpc-go 客户端经真实网络保存并查询 int32 最大值及二进制字节边界。
            String target = server.getListenAddress("grpc").getHostPort();
            try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, "boundary", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsMinimumIdOrder() throws Exception {
        // Meteor 通过公开入口发布普通订单服务，绑定示例自带的 Protobuf 契约。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // grpc-go 客户端经真实网络保存并查询 int32 最小值及二进制字节边界。
            String target = server.getListenAddress("grpc").getHostPort();
            String scenario = "negative-boundary";
            try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsEmptyOrder() throws Exception {
        // Meteor 通过公开入口发布普通订单服务，绑定示例自带的 Protobuf 契约。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // grpc-go 客户端经真实网络保存并查询 proto3 默认值和空字段。
            String target = server.getListenAddress("grpc").getHostPort();
            try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, "empty", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientReceivesMissingOrderFailure() throws Exception {
        // 发布没有任何订单的实现，错误由业务查询产生。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // grpc-go 必须收到可读业务错误，不能将连接失败当成缺失订单。
            String target = server.getListenAddress("grpc").getHostPort();
            try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, "missing", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("Internal", result.path("code").asText());
                assertTrue(result.toString(), result.path("message").asText().contains("Order not found: 404"));
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorSavesAndFindsUnicodeOrderOnGoServer() throws Exception {
        // grpc-go 独立服务监听临时端口，Meteor 只配置当前协议的出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "unicode", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 通过公开代理保存并查询中文、补充平面字符和二进制字段。
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscription);
            Order request = this.order(101, "订单-你好-🌍-café", new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            Order saved = remote.save(request);
            Order found = remote.find(this.query(request.getId()));

            // 保存响应与后续查询都必须保留同一笔订单的全部数据。
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 45000)
    public void meteorSavesAndFindsMaximumIdOrderOnGoServer() throws Exception {
        // grpc-go 独立服务监听临时端口，Meteor 只配置当前协议的出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "boundary", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 通过公开代理保存并查询 int32 最大值及二进制字节边界。
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscription);
            Order request = this.order(Integer.MAX_VALUE, "boundary", new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
            Order saved = remote.save(request);
            Order found = remote.find(this.query(request.getId()));

            // 保存响应与后续查询都必须保留同一笔订单的全部数据。
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 45000)
    public void meteorSavesAndFindsMinimumIdOrderOnGoServer() throws Exception {
        // grpc-go 独立服务监听临时端口，Meteor 只配置当前协议的出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "negative-boundary", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 通过公开代理保存并查询 int32 最小值及二进制字节边界。
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscription);
            byte[] attachment = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            Order request = this.order(Integer.MIN_VALUE, "negative-boundary", attachment);
            Order saved = remote.save(request);
            Order found = remote.find(this.query(request.getId()));

            // 保存响应与后续查询都必须保留同一笔订单的全部数据。
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 45000)
    public void meteorSavesAndFindsEmptyOrderOnGoServer() throws Exception {
        // grpc-go 独立服务监听临时端口，Meteor 只配置当前协议的出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "empty", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 通过公开代理保存并查询 proto3 默认值和空字段。
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscription);
            Order request = this.order(0, "", new byte[0]);
            Order saved = remote.save(request);
            Order found = remote.find(this.query(request.getId()));

            // 契约未声明 optional；线上省略字段回读为 proto3 默认的空字符串和空字节序列。
            assertEquals(0, saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(0, found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 45000)
    public void meteorReceivesMissingOrderFailureFromGoServer() throws Exception {
        // 启动没有订单的 grpc-go 服务，Meteor 从公开订阅 API 获得普通 Java 代理。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "missing", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscription);

            // 同步代理保留 Go 返回的 NOT_FOUND 状态和业务消息。
            OrderQuery query = this.query(404);
            UndeclaredThrowableException failure = assertThrows(UndeclaredThrowableException.class, () -> remote.find(query));
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(5, grpc.getCode());
            assertEquals("Order not found: 404", grpc.getDescription());
        }
    }

    private MetConfiguration configuration(boolean provider) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(10000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (provider) {
            http.bind("127.0.0.1", 0);
        }

        // 两种 TLS 模式使用与独立 Go 进程相同的 CA 和各自身份。
        if (!StringUtils.equals("plain", this.tlsMode)) {
            http.option("tlsEnabled", true) //
                    .option("tlsTrustStore", "classpath:/grpc/tls/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test") //
                    .option("tlsAlpn", "h2") //
                    .option("httpScheme", "https");
            if (provider) {
                http.option("tlsKeyStore", "classpath:/grpc/tls/acceptance/server.p12") //
                        .option("tlsKeyStorePassword", "meteor-test") //
                        .option("tlsClientAuth", StringUtils.equals("mtls", this.tlsMode) ? "REQUIRE" : "NONE");
            } else if (StringUtils.equals("mtls", this.tlsMode)) {
                http.option("tlsKeyStore", "classpath:/grpc/tls/acceptance/client.p12") //
                        .option("tlsKeyStorePassword", "meteor-test");
            }
        }
        http.protocol("grpc").addressScheme(this.scheme());
        return configuration;
    }

    private String scheme() {
        return StringUtils.equals("plain", this.tlsMode) ? "http" : "https";
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/order.proto"));
    }

    private Order order(int id, String description, byte[] attachment) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(attachment);
        return order;
    }

    private OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }
}
