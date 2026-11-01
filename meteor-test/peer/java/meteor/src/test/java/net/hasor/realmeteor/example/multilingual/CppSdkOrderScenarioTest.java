/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 C++ SDK 与 Meteor 的基本双向订单调用，不扩展 TLS、注册发现或类型矩阵。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/cpp/{0}/{1}@{2}/{3}", quoteTextArguments = false)
@MethodSource("versions")
@NxnRpc(name = "grpc", cases = "sdk/cpp/grpc/*")
@NxnRpc(name = "thrift", cases = "sdk/cpp/thrift/*")
public class CppSdkOrderScenarioTest {
    private final OrderProtocol protocol;
    private final String executable;

    public CppSdkOrderScenarioTest(String protocol, String sdk, String version, String profile, String executable) {
        this.protocol = OrderProtocol.valueOf(protocol.toUpperCase(Locale.ROOT));
        this.executable = executable;
    }
    public static List<Object[]> versions() {
        // 同一业务分别验证发行包基线和上游最新，生成器与运行库使用各自的独立前缀。
        List<Object[]> versions = new ArrayList<>();
        String grpcBaseline = "/opt/meteor-peer/native/cpp/grpc/order-peer";
        String grpcLatest = "/opt/meteor-peer/native/versions/grpc/1.85.0/cpp/order-peer";
        versions.add(new Object[] { "grpc", "gRPC-Core", "1.51.1", "debian-baseline", grpcBaseline });
        versions.add(new Object[] { "grpc", "gRPC-Core", "1.85.0", "upstream-latest", grpcLatest });
        String thriftBaseline = "/opt/meteor-peer/native/cpp/thrift/order-peer";
        String thriftLatest = "/opt/meteor-peer/native/versions/thrift/0.25.0/cpp/order-peer";
        versions.add(new Object[] { "thrift", "Apache-Thrift", "0.17.0", "debian-baseline", thriftBaseline });
        versions.add(new Object[] { "thrift", "Apache-Thrift", "0.25.0", "upstream-latest", thriftLatest });
        return versions;
    }

    @TempDir
    public Path data;

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sdkToMeteorOrderRoundTrip() throws Exception {
        // Meteor 从 bootstrap 启动，gRPC 根路径与 Thrift framed 配置分别沿用公开入口。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            String protocolName = this.protocol.protocolName();
            int port = provider.getListenAddress(protocolName).getPort();
            String target = protocolName + "://" + ContainerProcess.runnerHost() + ":" + port;

            // 官方 C++ SDK 在共享原生容器的独立进程内执行保存和查询。
            List<String> command = List.of(this.executable, "consumer");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "meteor-order", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorToSdkOrderRoundTrip() throws Exception {
        // 官方 C++ 服务独占动态端口；Meteor 只创建当前协议的出站端点。
        MetConfiguration configuration = this.configuration(false);
        List<String> command = List.of(this.executable, "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("Order provider is ready: "));
            consumer.start();
            MetBindInfo<?> binding = this.protocol.subscribe(consumer);
            String protocolName = this.protocol.protocolName();
            InterAddress address = new InterAddress(protocolName, target.getHost(), target.getPort(), "local");
            OrderDemoService orders = (OrderDemoService) consumer.getRsfClient(address).getRemote(binding);

            // 查询结果必须来自 C++ 存储，包含完整文本和二进制附件。
            Order expected = sample();
            orders.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = orders.find(query);
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    private MetConfiguration configuration(boolean listen) throws Exception {
        // 每例独占数据目录和临时端口，根路径与标准生成 SDK 的方法路径一致。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setDataHome(Files.createTempDirectory(this.data, "case-"));
        String transport = "tcp";
        if (this.protocol == OrderProtocol.GRPC) {
            transport = "http";
        }
        var connector = configuration.connector(transport);
        if (listen) {
            connector.bind("0.0.0.0", 0);
        }
        if (this.protocol == OrderProtocol.GRPC) {
            connector.option("httpVersion", 2);
            connector.protocol("grpc").contextPath("/").option("serializeType", "Protobuf");
        } else {
            connector.protocol("thrift");
        }
        return configuration;
    }

    private static Order sample() {
        Order order = new Order();
        order.setId(1001);
        order.setDescription("meteor-order");
        order.setAttachment(new byte[] { 77, 101, 116, 101, 111, 114 });
        return order;
    }
}
