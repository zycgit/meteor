/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.compatibility.go;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** grpc-go 和 Connect-Go 的发布版本增量验证，复用同一份订单契约与实际网络入口。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/go/{0}/{1}@{2}/{3}/{4}", quoteTextArguments = false)
@MethodSource("versions")
@NxnRpc(name = "grpc", cases = "sdk/go/grpc/*")
@NxnRpc(name = "connect", version = "1", cases = "sdk/go/connect/*")
public class GoProtoSdkVersionCompatibilityTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";
    private final String protocol;
    private final String direction;
    private final String executable;

    public GoProtoSdkVersionCompatibilityTest(String protocol, String sdk, String version, String profile,
            String direction, String executable) {
        this.protocol = protocol;
        this.direction = direction;
        this.executable = executable;
    }
    public static Collection<Object[]> versions() {
        // 基线与新增最新版本使用不同二进制；每个方向单独报告。
        List<Object[]> versions = new ArrayList<>();
        add(versions, "grpc", "grpc-go", "1.84.0", "protobuf-http2", "/opt/meteor-peer/go/order-peer");
        add(versions, "grpc", "grpc-go", "1.85.0", "protobuf-http2", //
                "/opt/meteor-peer/go/compatibility/grpc-1.85.0-peer");
        add(versions, "connect", "Connect-Go", "1.19.1", "protobuf-http1", "/opt/meteor-peer/go/order-peer");
        add(versions, "connect", "Connect-Go", "1.21.1", "protobuf-http1", //
                "/opt/meteor-peer/go/compatibility/connect-1.21.1-peer");
        return versions;
    }

    private static void add(List<Object[]> rows, String protocol, String sdk, String version, String profile,
            String executable) {
        rows.add(new Object[] { protocol, sdk, version, profile, "SDK-to-Meteor", executable });
        rows.add(new Object[] { protocol, sdk, version, profile, "Meteor-to-SDK", executable });
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unicodeOrderPreservesBinaryPayload() throws Exception {
        // SDK 对端与 Meteor 共用契约，Java 侧通过 bootstrap 发布和订阅。
        boolean sdkConsumer = StringUtils.equals("SDK-to-Meteor", this.direction);
        MetConfiguration configuration = this.configuration(sdkConsumer);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = this.publish(context, sdkConsumer);
            String role = sdkConsumer ? "client" : "server";
            String target = sdkConsumer ? this.target(context) : null;
            try (GoPeerProcess peer = this.peer(role, "unicode", target)) {
                if (sdkConsumer) {
                    JsonNode outcome = peer.awaitResult();
                    assertTrue(outcome.path("ok").asBoolean(), outcome.toString());
                } else {
                    OrderDemoService remote = context.getRsfClient(this.address(peer)).getRemote(service);
                    Order request = new Order();
                    request.setId(101);
                    request.setDescription("订单-你好-🌍-café");
                    request.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
                    Order saved = remote.save(request);

                    // 标量、Unicode 和二进制字段共同验证一次订单响应。
                    assertEquals(request.getId(), saved.getId());
                    assertEquals(request.getDescription(), saved.getDescription());
                    assertArrayEquals(request.getAttachment(), saved.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingOrderPreservesApplicationFailure() throws Exception {
        // 没有订单的服务只产生业务查询异常；对端不能将断连或超时判成通过。
        boolean sdkConsumer = StringUtils.equals("SDK-to-Meteor", this.direction);
        MetConfiguration configuration = this.configuration(sdkConsumer);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = this.publish(context, sdkConsumer);
            String role = sdkConsumer ? "client" : "server";
            String target = sdkConsumer ? this.target(context) : null;
            try (GoPeerProcess peer = this.peer(role, "missing", target)) {
                if (sdkConsumer) {
                    JsonNode outcome = peer.awaitResult();
                    assertTrue(outcome.path("ok").asBoolean(), outcome.toString());
                    assertTrue(outcome.path("message").asText().contains("Order not found: 404"), outcome.toString());
                } else {
                    OrderQuery query = new OrderQuery();
                    query.setId(404);
                    var pending = context.getRsfClient(this.address(peer)).asyncInvoke(service, "find", //
                            new Class<?>[] { OrderQuery.class }, new Object[] { query });
                    ExecutionException failure = assertThrows(ExecutionException.class, //
                            () -> pending.getData(10, TimeUnit.SECONDS));
                    assertTrue(failure.getCause().getMessage().contains("Order not found: 404"), failure.toString());
                }
            }
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.setDefaultTimeout(10000);
        var connector = configuration.connector("http");
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.option("httpVersion", StringUtils.equals("grpc", this.protocol) ? 2 : 1);
        connector.protocol(this.protocol).contextPath("/");
        return configuration;
    }

    private MetBindInfo<OrderDemoService> publish(MetContext context, boolean provider) throws Exception {
        OrderDemoService implementation = provider ? new OrderDemoServiceImpl() : null;
        var builder = context.publisher().rsfService(OrderDemoService.class, implementation) //
                .name(SERVICE).protocol(this.protocol).aliasName(this.protocol, SERVICE).serialize("Protobuf");
        var contract = this.getClass().getResource("/protocol/proto/order.proto");
        builder.bindResource(this.protocol, new GrpcProtoResource(contract));
        return builder.register();
    }

    private String target(MetContext context) {
        String authority = context.getListenAddress(this.protocol).getHostPort();
        if (StringUtils.equals("connect", this.protocol)) {
            return "http://" + authority;
        }
        return authority;
    }

    private InterAddress address(GoPeerProcess peer) throws Exception {
        String target = peer.awaitReady();
        URI endpoint = URI.create(target.contains("://") ? target : "tcp://" + target);
        return new InterAddress(this.protocol, endpoint.getHost(), endpoint.getPort(), "default");
    }

    private GoPeerProcess peer(String role, String scenario, String target) throws Exception {
        return GoPeerProcess.startBinary(this.executable, this.protocol, role, "plain", scenario, target);
    }
}
