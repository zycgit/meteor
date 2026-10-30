/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.go;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.apache.thrift.TApplicationException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Apache Thrift Go 与 Meteor 双向直连，逐项验证编码、分帧和传输安全组合。 */
@RunWith(Parameterized.class)
public class ThriftGoInteropTest {
    private final String  encoding;
    private final boolean framed;
    private final String  tlsMode;

    public ThriftGoInteropTest(String encoding, boolean framed, String tlsMode) {
        this.encoding = encoding;
        this.framed = framed;
        this.tlsMode = tlsMode;
    }

    @Parameterized.Parameters(name = "encoding={0},framed={1},tls={2}")
    public static Collection<Object[]> configurations() {
        // 三种编码、两种分帧与三种安全模式分别生成独立 JUnit 参数实例。
        List<Object[]> configurations = new ArrayList<>();
        for (String encoding : new String[] { "binary", "compact", "json" }) {
            for (boolean framed : new boolean[] { true, false }) {
                for (String tlsMode : new String[] { "plain", "tls", "mtls" }) {
                    configurations.add(new Object[] { encoding, framed, tlsMode });
                }
            }
        }
        return configurations;
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsUnicodeOrder() throws Exception {
        // Meteor 从公开 bootstrap 启动，直接发布普通注解接口和共享订单业务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            OrderProtocol.THRIFT.publish(server, new OrderDemoServiceImpl());

            // 官方 Go 客户端通过选定线格式与安全模式保存并查询中文、补充平面字符及二进制字段。
            String target = server.getListenAddress("thrift", this.scheme()).getHostPort();
            String scenario = "unicode";
            try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsMaximumIdOrder() throws Exception {
        // Meteor 从公开 bootstrap 启动，直接发布普通注解接口和共享订单业务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            OrderProtocol.THRIFT.publish(server, new OrderDemoServiceImpl());

            // 官方 Go 客户端通过选定线格式与安全模式保存并查询int32 最大值及二进制字节边界。
            String target = server.getListenAddress("thrift", this.scheme()).getHostPort();
            String scenario = "boundary";
            try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsMinimumIdOrder() throws Exception {
        // Meteor 从公开 bootstrap 启动，直接发布普通注解接口和共享订单业务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            OrderProtocol.THRIFT.publish(server, new OrderDemoServiceImpl());

            // 官方 Go 客户端通过选定线格式与安全模式保存并查询int32 最小值及二进制字节边界。
            String target = server.getListenAddress("thrift", this.scheme()).getHostPort();
            String scenario = "negative-boundary";
            try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientSavesAndFindsEmptyOrder() throws Exception {
        // Meteor 从公开 bootstrap 启动，直接发布普通注解接口和共享订单业务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            OrderProtocol.THRIFT.publish(server, new OrderDemoServiceImpl());

            // 官方 Go 客户端通过选定线格式与安全模式保存并查询零 ID、空字符串和零长度二进制字段。
            String target = server.getListenAddress("thrift", this.scheme()).getHostPort();
            String scenario = "empty";
            try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "client", this.tlsMode, scenario, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void goClientReceivesMissingOrderFailure() throws Exception {
        // 提供端没有预置订单，缺失查询必须由业务层产生可辨识的异常。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            OrderProtocol.THRIFT.publish(server, new OrderDemoServiceImpl());

            // Go 对端检查标准 Thrift 异常，连接失败或请求超时不能算作业务失败。
            String target = server.getListenAddress("thrift", this.scheme()).getHostPort();
            try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "client", this.tlsMode, "missing", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertTrue(result.toString(), result.path("message").asText().contains("Order not found: 404"));
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorSavesAndFindsUnicodeOrderOnGoServer() throws Exception {
        // 官方 Go 服务独立监听临时端口；Meteor 仅配置 Thrift 出站能力。
        MetConfiguration configuration = this.configuration(false);
        String scenario = "unicode";
        try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "server", this.tlsMode, scenario, null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<?> subscription = OrderProtocol.THRIFT.subscribe(client);
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/" + DirectRpcExample.UNIT_NAME);
            MetClient remote = client.getRsfClient(address);

            // 公开客户端直接使用普通注解接口，业务入参覆盖中文、补充平面字符及二进制字段。
            byte[] attachment = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            Order request = this.order(101, "订单-你好-🌍-café", attachment);
            Order saved = OrderProtocol.THRIFT.save(remote, subscription, request);
            Order found = OrderProtocol.THRIFT.find(remote, subscription, request.getId());

            // 保存响应与后续查询都保留 ID、文本和二进制字段，空字段保持显式空值。
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
        // 官方 Go 服务独立监听临时端口；Meteor 仅配置 Thrift 出站能力。
        MetConfiguration configuration = this.configuration(false);
        String scenario = "boundary";
        try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "server", this.tlsMode, scenario, null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<?> subscription = OrderProtocol.THRIFT.subscribe(client);
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/" + DirectRpcExample.UNIT_NAME);
            MetClient remote = client.getRsfClient(address);

            // 公开客户端直接使用普通注解接口，业务入参覆盖int32 最大值及二进制字节边界。
            byte[] attachment = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            Order request = this.order(Integer.MAX_VALUE, "boundary", attachment);
            Order saved = OrderProtocol.THRIFT.save(remote, subscription, request);
            Order found = OrderProtocol.THRIFT.find(remote, subscription, request.getId());

            // 保存响应与后续查询都保留 ID、文本和二进制字段，空字段保持显式空值。
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
        // 官方 Go 服务独立监听临时端口；Meteor 仅配置 Thrift 出站能力。
        MetConfiguration configuration = this.configuration(false);
        String scenario = "negative-boundary";
        try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "server", this.tlsMode, scenario, null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<?> subscription = OrderProtocol.THRIFT.subscribe(client);
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/" + DirectRpcExample.UNIT_NAME);
            MetClient remote = client.getRsfClient(address);

            // 公开客户端直接使用普通注解接口，业务入参覆盖int32 最小值及二进制字节边界。
            byte[] attachment = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            Order request = this.order(Integer.MIN_VALUE, "negative-boundary", attachment);
            Order saved = OrderProtocol.THRIFT.save(remote, subscription, request);
            Order found = OrderProtocol.THRIFT.find(remote, subscription, request.getId());

            // 保存响应与后续查询都保留 ID、文本和二进制字段，空字段保持显式空值。
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
        // 官方 Go 服务独立监听临时端口；Meteor 仅配置 Thrift 出站能力。
        MetConfiguration configuration = this.configuration(false);
        String scenario = "empty";
        try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "server", this.tlsMode, scenario, null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<?> subscription = OrderProtocol.THRIFT.subscribe(client);
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/" + DirectRpcExample.UNIT_NAME);
            MetClient remote = client.getRsfClient(address);

            // 公开客户端直接使用普通注解接口，业务入参覆盖零 ID、空字符串和零长度二进制字段。
            byte[] attachment = new byte[0];
            Order request = this.order(0, "", attachment);
            Order saved = OrderProtocol.THRIFT.save(remote, subscription, request);
            Order found = OrderProtocol.THRIFT.find(remote, subscription, request.getId());

            // 保存响应与后续查询都保留 ID、文本和二进制字段，空字段保持显式空值。
            assertEquals(request.getId(), saved.getId());
            assertEquals(request.getDescription(), saved.getDescription());
            assertArrayEquals(request.getAttachment(), saved.getAttachment());
            assertEquals(request.getId(), found.getId());
            assertEquals(request.getDescription(), found.getDescription());
            assertArrayEquals(request.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 45000)
    public void meteorReceivesMissingOrderFailureFromGoServer() throws Exception {
        // Go 服务保持空订单存储，Meteor 通过 bootstrap 订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start(this.variant(), "server", this.tlsMode, "missing", null); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitReady();
            client.start();
            MetBindInfo<?> subscription = OrderProtocol.THRIFT.subscribe(client);
            InterAddress address = new InterAddress(this.scheme() + "://" + target + "/" + DirectRpcExample.UNIT_NAME);
            MetClient remote = client.getRsfClient(address);

            // Future 中保留对端应用异常类型和业务消息，不接受网络超时代替。
            OrderQuery query = new OrderQuery();
            query.setId(404);
            var pending = remote.asyncInvoke(subscription, "find", new Class<?>[] { OrderQuery.class }, //
                    new Object[] { query });
            ExecutionException rejected = assertThrows(ExecutionException.class, //
                    () -> pending.getData(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof TApplicationException);
            TApplicationException failure = (TApplicationException) rejected.getCause();
            assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
            assertEquals("Internal error processing find: Order not found: 404", failure.getMessage());
        }
    }

    private MetConfiguration configuration(boolean provider) {
        // 复用订单示例的单端点配置，只修改当前挂载的线格式与安全选项。
        MetConfiguration configuration = provider ? OrderProtocol.THRIFT.providerConfiguration() //
                : OrderProtocol.THRIFT.consumerConfiguration();
        if (provider) {
            configuration.getConnectors().get(0).bind(ContainerProcess.runnerHost(), 0);
        }
        configuration.setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000);
        var tcp = configuration.getConnectors().get(0);
        var mount = tcp.getProtocols().get(0);
        mount.option("encoding", this.encoding).option("framed", this.framed).addressScheme(this.scheme());

        // Go 与 Java 使用同一 CA；服务端认证提供服务身份，双向认证再提供客户端身份。
        if (!StringUtils.equals("plain", this.tlsMode)) {
            tcp.option("tlsEnabled", true) //
                    .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test");
            if (provider) {
                String clientAuth = StringUtils.equals("mtls", this.tlsMode) ? "REQUIRE" : "NONE";
                tcp.option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                        .option("tlsKeyStorePassword", "meteor-test") //
                        .option("tlsClientAuth", clientAuth);
            } else if (StringUtils.equals("mtls", this.tlsMode)) {
                tcp.option("tlsKeyStore", "classpath:/certs/acceptance/client.p12") //
                        .option("tlsKeyStorePassword", "meteor-test");
            }
        }
        return configuration;
    }

    private String variant() {
        String framing = this.framed ? "framed" : "unframed";
        return "thrift-" + this.encoding + "-" + framing;
    }

    private String scheme() {
        return StringUtils.equals("plain", this.tlsMode) ? "thrift" : "thrift-tls";
    }

    private Order order(int id, String description, byte[] attachment) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(attachment);
        return order;
    }
}
