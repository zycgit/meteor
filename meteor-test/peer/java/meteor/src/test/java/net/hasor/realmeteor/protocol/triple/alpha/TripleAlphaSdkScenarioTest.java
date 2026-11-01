/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.triple.alpha;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** Apache Dubbo JavaScript 3.0.0-alpha SDK 的最小 Triple 互通，不代表稳定版 SDK。 */
@NxnRpc(name = "triple")
public class TripleAlphaSdkScenarioTest {
    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void apacheNodeSdkCallsMeteorTripleProtobuf() throws Exception {
        // Meteor 只发布 Triple 协议，SDK 消费者通过其公开 gRPC/Protobuf 兼容入口调用。
        MetConfiguration configuration = this.configuration(true, 2, "grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("triple").aliasName("triple", OrderExampleUtils.SERVICE_NAME) //
                    .serialize("Protobuf").bindResource("triple", resource).register();
            int port = server.getListenAddress("triple").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port;

            // 锁定的 Apache Node SDK 自行保存、查询并断言完整订单，不借用 Connect SDK。
            try (NodeRpcPeer consumer = NodeRpcPeer.start("triple-alpha", "client", target)) {
                consumer.requireSuccess();
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTripleProtobufCallsApacheNodeSdk() throws Exception {
        // Apache Node SDK 仅发布 gRPC 兼容面；Meteor 消费端仍明确选择 Triple 协议。
        MetConfiguration configuration = this.configuration(false, 2, "grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (NodeRpcPeer provider = NodeRpcPeer.start("triple-alpha", "server"); //
             MetContext client = configuration.buildContext()) {
            int port = provider.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> binding = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("triple").aliasName("triple", OrderExampleUtils.SERVICE_NAME) //
                    .serialize("Protobuf").bindResource("triple", resource).register();
            InterAddress address = new InterAddress("triple", provider.host(), port, "default");
            OrderDemoService orders = client.getRsfClient(address).getRemote(binding);

            // 使用普通 Java 接口读写 SDK 提供者的订单，校验真实跨容器返回值。
            Order input = OrderExampleUtils.newOrder(1001, "triple-alpha");
            orders.save(input);
            OrderQuery query = new OrderQuery();
            query.setId(input.getId());
            Order result = orders.find(query);
            assertEquals(input.getId(), result.getId());
            assertEquals(input.getDescription(), result.getDescription());
            assertArrayEquals(input.getAttachment(), result.getAttachment());
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void apacheBrowserSdkCallsMeteorTripleHttpProtobuf() throws Exception {
        // Web SDK 的 Triple unary 使用 HTTP/1 裸 Protobuf，与 gRPC-Web 场景分开。
        MetConfiguration configuration = this.configuration(true, 1, "proto");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("triple").aliasName("triple", OrderExampleUtils.SERVICE_NAME) //
                    .serialize("Protobuf").bindResource("triple", resource).register();
            int port = server.getListenAddress("triple").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/";
            Path peers = Path.of(System.getProperty("meteor.test.peerDirectory")).toAbsolutePath();
            Path script = peers.resolve("browser/triple-alpha-consumer.cjs");
            List<String> command = List.of("node", script.toString(), target);
            Path log = Files.createTempFile("triple-alpha-browser-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            Process browser = ContainerProcess.start("browser", builder);
            try {
                // Chromium 保持浏览器安全策略，由 Apache Web SDK 发起真实业务 HTTP 请求。
                boolean finished = browser.waitFor(30, TimeUnit.SECONDS);
                String output = Files.readString(log, StandardCharsets.UTF_8);
                assertTrue(finished, "Browser Triple SDK timed out:\n" + output);
                assertEquals(0, browser.exitValue(), "Browser Triple SDK failed:\n" + output);
                List<String> lines = output.lines().filter(line -> line.startsWith("{")).toList();
                assertEquals(1, lines.size(), "Browser SDK must return exactly one order:\n" + output);
                JsonNode result = new ObjectMapper().readTree(lines.get(0));
                assertEquals(1001, result.required("id").intValue());
                assertEquals("Order from Apache Dubbo Web alpha", result.required("description").textValue());
                assertEquals("TWV0ZW9y", result.required("attachment").textValue());
            } finally {
                // 场景只回收自己创建的浏览器进程，失败日志同时保留在统一进程归档中。
                if (browser.isAlive()) {
                    browser.destroyForcibly();
                    browser.waitFor(5, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(log);
            }
        }
    }

    private MetConfiguration configuration(boolean listening, int version, String mode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(10000);
        var http = configuration.connector("http").option("httpVersion", version);
        if (listening) {
            http.bind("0.0.0.0", 0);
        }
        http.protocol("triple").contextPath("/").option("httpMode", mode);
        return configuration;
    }
}
