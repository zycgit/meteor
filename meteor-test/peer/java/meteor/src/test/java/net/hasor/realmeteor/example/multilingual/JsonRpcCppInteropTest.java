/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** libjson-rpc-cpp SDK 的基础双向文本互通，不扩展传输与类型矩阵。 */
@RunWith(Parameterized.class)
public class JsonRpcCppInteropTest {
    private final String executable;

    public JsonRpcCppInteropTest(String version, String profile, String executable) {
        this.executable = executable;
    }

    @Parameterized.Parameters(name = "sdk/cpp/jsonrpc/libjson-rpc-cpp@{0}/{1}")
    public static List<Object[]> versions() {
        // 两个主版本加载不同前缀的运行库，测试保留相同业务契约。
        List<Object[]> versions = new ArrayList<>();
        versions.add(new Object[] { "0.7.0", "legacy-debian", "/opt/meteor-peer/native/jsonrpc/cpp/echo-peer" });
        String latest = "/opt/meteor-peer/native/versions/jsonrpc/1.4.1/cpp/echo-peer";
        versions.add(new Object[] { "1.4.1", "upstream-latest", latest });
        return versions;
    }

    @Test(timeout = 60000)
    public void sdkToMeteorJsonRpcEcho() throws Exception {
        // 通过 bootstrap 启动 Meteor，发布普通 Java 接口到一个 JSON-RPC HTTP 挂载点。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .aliasName("jsonrpc", "demo").protocol("jsonrpc").serialize("Json").register();

            // 独立 C++ SDK 构造一次 V2 请求，并自行检查 Unicode 回声结果。
            int port = provider.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            String message = "libjson-rpc-cpp → Meteor 中文 <&>";
            List<String> command = List.of(this.executable, "consumer", message);
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output, output.contains("OK libjson-rpc-cpp: " + message));
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorToSdkJsonRpcEcho() throws Exception {
        // libjson-rpc-cpp 自行发布 HTTP 服务，Meteor 只配置出站连接器。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("jsonrpc").contextPath("/rpc");
        List<String> command = List.of(this.executable, "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            String target = provider.awaitReady("READY ");
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .aliasName("jsonrpc", "demo").protocol("jsonrpc").serialize("Json").register();

            // 订阅没有本地实现的服务，真实网络响应由 C++ AbstractServer 生成。
            TextEchoService remote = consumer.getRsfClient(new InterAddress(target)).getRemote(service);
            String message = "Meteor → libjson-rpc-cpp 中文 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
