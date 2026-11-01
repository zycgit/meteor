/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Apache Thrift C GLib 与 Meteor 的基本双向调用；只验证一个 String echo。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/c/thrift/Apache-Thrift@{0}/{1}", quoteTextArguments = false)
@MethodSource("versions")
@NxnRpc(name = "thrift")
public class CThriftSdkScenarioTest {
    private final String executable;

    public CThriftSdkScenarioTest(String version, String profile, String executable) {
        this.executable = executable;
    }
    public static List<Object[]> versions() {
        // 每份 IDL 绑定与生成器、运行库属于同一发布版本。
        List<Object[]> versions = new ArrayList<>();
        versions.add(new Object[] { "0.17.0", "debian-baseline", "/opt/meteor-peer/native/c/thrift/echo-peer" });
        String latest = "/opt/meteor-peer/native/versions/thrift/0.25.0/c/echo-peer";
        versions.add(new Object[] { "0.25.0", "upstream-latest", latest });
        return versions;
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sdkToMeteorThriftEcho() throws Exception {
        // 普通 Java 接口从 bootstrap 发布，参数公共注解编号1对应测试自有 IDL。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("0.0.0.0", 0).protocol("thrift");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, value -> value) //
                    .protocol("thrift").aliasName("thrift", "EchoService").register();
            int port = provider.getListenAddress("thrift").getPort();
            String target = "thrift://" + ContainerProcess.runnerHost() + ":" + port;

            // 官方 C GLib 生成客户端在共享原生容器的独立进程中执行一次真实 echo。
            List<String> command = List.of(this.executable, "consumer");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("RESULT C GLib Thrift 中文"), output);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorToSdkThriftEcho() throws Exception {
        // 官方 C GLib SimpleServer 独立监听；本场景只发起一次请求，不要求并发服务能力。
        List<String> command = List.of(this.executable, "provider");
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("thrift");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("Order provider is ready: "));
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .protocol("thrift").aliasName("thrift", "EchoService").register();
            InterAddress address = new InterAddress("thrift", target.getHost(), target.getPort(), "default");
            TextEchoService remote = consumer.getRsfClient(address).getRemote(service);

            // 只有对端官方 Processor 返回的字符串才作为此方向的通过证据。
            String expected = "Meteor C GLib 中文";
            assertEquals(expected, remote.echo(expected));
        }
    }
}
