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
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 上游 libtirpc 与发行包基线并存，C 和 C++ 分别通过独立订单进程进行双向互通。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/{0}/oncrpc/libtirpc@1.3.8/upstream-latest", quoteTextArguments = false)
@MethodSource("versions")
@NxnRpc(name = "oncrpc", version = "2")
public class NativeSdkVersionScenarioTest {
    @TempDir
    public Path data;

    private final String executableDirectory;

    public NativeSdkVersionScenarioTest(String language) {
        this.executableDirectory = "/opt/meteor-peer/native/versions/oncrpc/1.3.8/" + language;
    }
    public static List<Object[]> versions() {
        // 每种语言使用以新版 libtirpc 独立编译的二进制，系统基线仍由原场景验证。
        List<Object[]> versions = new ArrayList<>();
        versions.add(new Object[] { "c" });
        versions.add(new Object[] { "cpp" });
        return versions;
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sdkToMeteorOrderRoundTrip() throws Exception {
        // Meteor 经公开 bootstrap 发布 ONC RPC，使用当前场景的动态 TCP 端口。
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setDataHome(Files.createTempDirectory(this.data, "case-"));
        OrderProtocol.ONCRPC.mount(configuration.connector("tcp").bind("0.0.0.0", 0));
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            OrderProtocol.ONCRPC.publish(provider, new OrderDemoServiceImpl());
            int port = provider.getListenAddress("oncrpc").getPort();
            String target = "oncrpc://" + ContainerProcess.runnerHost() + ":" + port;

            // 新版原生 SDK 执行保存再查询，JUnit 核对实际返回的文本和二进制附件。
            List<String> command = List.of(this.executableDirectory + "/consumer");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                JsonNode result = consumer.awaitResult();
                OrderPeerProcess.assertOrder(result, "meteor-order", "TWV0ZW9y");
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorToSdkOrderRoundTrip() throws Exception {
        // libtirpc 提供者自行监听动态端口；Meteor 仅配置 ONC RPC 出站能力。
        MetConfiguration configuration = OrderProtocol.ONCRPC.consumerConfiguration();
        configuration.setDataHome(Files.createTempDirectory(this.data, "case-"));
        List<String> command = List.of(this.executableDirectory + "/provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("Order provider is ready: "));
            consumer.start();
            MetBindInfo<?> subscription = OrderProtocol.ONCRPC.subscribe(consumer);
            InterAddress address = new InterAddress("oncrpc", target.getHost(), target.getPort(), "local");
            MetClient client = consumer.getRsfClient(address);

            // 两次网络调用访问独立 SDK 的业务存储，不能由 Meteor 本地实现代答。
            Order expected = new Order();
            expected.setId(1001);
            expected.setDescription("Meteor to libtirpc");
            expected.setAttachment(new byte[] { 77, 101, 116, 101, 111, 114 });
            OrderProtocol.ONCRPC.save(client, subscription, expected);
            Order found = OrderProtocol.ONCRPC.find(client, subscription, expected.getId());
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }
}
