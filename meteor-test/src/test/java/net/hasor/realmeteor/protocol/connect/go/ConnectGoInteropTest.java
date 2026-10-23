/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect.go;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URL;
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
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 普通订单接口与官方 Connect Go 对端双向互通，分别记录提供者与消费者场景。 */
@RunWith(Parameterized.class)
public class ConnectGoInteropTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";
    private final        String tlsMode;

    public ConnectGoInteropTest(String tlsMode) {
        this.tlsMode = tlsMode;
    }

    @Parameterized.Parameters(name = "tls={0}")
    public static Object[][] tlsModes() {
        return new Object[][] { { "plain" }, { "tls" }, { "mtls" } };
    }

    @Test(timeout = 40000)
    public void providerGoClientReadsUnicodeAndBinary() throws Exception {
        // Meteor 只挂载 Connect；双方通过同一份公开订单 proto 解释请求和响应。
        MetConfiguration configuration = this.configuration(true);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            String target = this.scheme() + "://" + server.getListenAddress("connect").getHostPort();

            // 独立 Go 客户端执行本场景的保存和查询，Java 侧核对进程成功退出后的结果。
            try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerMeteorReadsUnicodeAndBinary() throws Exception {
        // Go 服务端自己选择空闲端口，Meteor 消费端只声明出站的 Connect 能力。
        MetConfiguration configuration = this.configuration(false);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "unicode", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort(),
                    "default");
            OrderDemoService remote = client.getRsfClient(target).getRemote(subscription);

            // 保存和查询同一笔订单，分别验证两次真实 RPC 的返回值。
            Order request = order(101, "订单-你好-🌍-café", new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
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

    @Test(timeout = 40000)
    public void providerGoClientReadsPositiveInt32Boundary() throws Exception {
        // Meteor 只挂载 Connect；双方通过同一份公开订单 proto 解释请求和响应。
        MetConfiguration configuration = this.configuration(true);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            String target = this.scheme() + "://" + server.getListenAddress("connect").getHostPort();

            // 独立 Go 客户端执行本场景的保存和查询，Java 侧核对进程成功退出后的结果。
            try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, "boundary", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerMeteorReadsPositiveInt32Boundary() throws Exception {
        // Go 服务端自己选择空闲端口，Meteor 消费端只声明出站的 Connect 能力。
        MetConfiguration configuration = this.configuration(false);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "boundary", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort(),
                    "default");
            OrderDemoService remote = client.getRsfClient(target).getRemote(subscription);

            // 保存和查询同一笔订单，分别验证两次真实 RPC 的返回值。
            Order request = order(Integer.MAX_VALUE, "boundary", new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
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

    @Test(timeout = 40000)
    public void providerGoClientReadsNegativeInt32Boundary() throws Exception {
        // Meteor 只挂载 Connect；双方通过同一份公开订单 proto 解释请求和响应。
        MetConfiguration configuration = this.configuration(true);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            String target = this.scheme() + "://" + server.getListenAddress("connect").getHostPort();

            // 独立 Go 客户端执行本场景的保存和查询，Java 侧核对进程成功退出后的结果。
            String scenario = "negative-boundary";
            try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerMeteorReadsNegativeInt32Boundary() throws Exception {
        // Go 服务端自己选择空闲端口，Meteor 消费端只声明出站的 Connect 能力。
        MetConfiguration configuration = this.configuration(false);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "negative-boundary", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort(),
                    "default");
            OrderDemoService remote = client.getRsfClient(target).getRemote(subscription);

            // 保存和查询同一笔订单，分别验证两次真实 RPC 的返回值。
            byte[] attachment = { 0, 1, 127, (byte) 128, (byte) 255 };
            Order request = order(Integer.MIN_VALUE, "negative-boundary", attachment);
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

    @Test(timeout = 40000)
    public void providerGoClientReadsEmptyProto3Fields() throws Exception {
        // Meteor 只挂载 Connect；双方通过同一份公开订单 proto 解释请求和响应。
        MetConfiguration configuration = this.configuration(true);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            String target = this.scheme() + "://" + server.getListenAddress("connect").getHostPort();

            // 独立 Go 客户端执行本场景的保存和查询，Java 侧核对进程成功退出后的结果。
            try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, "empty", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerMeteorReadsEmptyProto3Fields() throws Exception {
        // Go 服务端自己选择空闲端口，Meteor 消费端只声明出站的 Connect 能力。
        MetConfiguration configuration = this.configuration(false);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "empty", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort(),
                    "default");
            OrderDemoService remote = client.getRsfClient(target).getRemote(subscription);

            // 保存和查询同一笔订单，分别验证两次真实 RPC 的返回值。
            Order request = order(0, "", new byte[0]);
            Order saved = remote.save(request);
            OrderQuery query = new OrderQuery();
            query.setId(request.getId());
            Order found = remote.find(query);
            // proto3 的默认字段未出现在响应 JSON 中，普通 POJO 保留 Java 字段默认值。
            assertEquals(0, saved.getId());
            assertNull(saved.getDescription());
            assertNull(saved.getAttachment());
            assertEquals(0, found.getId());
            assertNull(found.getDescription());
            assertNull(found.getAttachment());
        }
    }

    @Test(timeout = 40000)
    public void providerGoClientReadsMissingOrderFailure() throws Exception {
        // Meteor 只挂载 Connect；双方通过同一份公开订单 proto 解释请求和响应。
        MetConfiguration configuration = this.configuration(true);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            String target = this.scheme() + "://" + server.getListenAddress("connect").getHostPort();

            // 独立 Go 客户端执行不存在订单的查询并检查实际业务错误。
            try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, "missing", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("invalid_argument", result.path("code").asText());
                String message = result.path("message").asText();
                assertTrue(message, message.contains("Order not found: 404"));
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerMeteorReceivesMissingOrderFailure() throws Exception {
        // Go 按 Connect 标准返回 not_found，客户端使用公开订阅与同步代理。
        MetConfiguration configuration = this.configuration(false);
        URL descriptor = this.getClass().getResource("/proto/order.proto");
        GrpcProtoResource resource = new GrpcProtoResource(descriptor);
        try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "missing", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<OrderDemoService> subscription = client.publisher().rsfService(OrderDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize("Protobuf") //
                    .bindResource("connect", resource).register();
            InterAddress target = new InterAddress(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort(),
                    "default");
            OrderDemoService remote = client.getRsfClient(target).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(404);

            // 同步接口未声明受检异常，JDK 代理包装的异常链仍须保留精确协议错误。
            UndeclaredThrowableException failure = assertThrows(UndeclaredThrowableException.class,
                    () -> remote.find(query));
            ConnectStatusException status = ConnectStatusException.find(failure);
            assertNotNull(failure.toString(), status);
            assertEquals("not_found", status.getCode());
            assertEquals("Order not found: 404", status.getDescription());
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", 1);
        if (listening) {
            connector.bind("127.0.0.1", 0);
        }
        if (!StringUtils.equals("plain", this.tlsMode)) {
            connector.option("tlsEnabled", true).option("httpScheme", "https") //
                    .option("tlsTrustStore", "classpath:/grpc/tls/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test").option("tlsAlpn", "http/1.1");
            if (listening) {
                connector.option("tlsKeyStore", "classpath:/grpc/tls/acceptance/server.p12") //
                        .option("tlsKeyStorePassword", "meteor-test") //
                        .option("tlsClientAuth", StringUtils.equals("mtls", this.tlsMode) ? "REQUIRE" : "NONE");
            } else if (StringUtils.equals("mtls", this.tlsMode)) {
                connector.option("tlsKeyStore", "classpath:/grpc/tls/acceptance/client.p12") //
                        .option("tlsKeyStorePassword", "meteor-test");
            }
        }
        connector.protocol("connect").addressScheme(this.scheme()).contextPath("/");
        return configuration;
    }

    private String scheme() {
        return StringUtils.equals("plain", this.tlsMode) ? "http" : "https";
    }

    private static Order order(int id, String description, byte[] attachment) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(attachment);
        return order;
    }
}
