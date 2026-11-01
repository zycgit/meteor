/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 C++ 1x 源码 SDK，经公开传输扩展接入 libcurl；未使用上游损坏的 Asio HTTP 客户端。 */
@NxnRpc(name = "hprose")
public class HproseCppSourceSdkScenarioTest {
    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sourceSdkWithPublicCurlTransportCallsMeteorProvider() throws Exception {
        // Meteor 仅挂载当前协议，服务实现通过公开 bootstrap 和发布 API 准备。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("0.0.0.0", 0).protocol("hprose").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .protocol("hprose").aliasName("Hprose", "sample").serialize("Hprose").register();
            int port = provider.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";

            // 独立 C++ SDK 以公开 sendAndReceive 扩展使用 libcurl，不拼接或解析 Hprose 报文。
            List<String> command = List.of("/opt/meteor-peer/native/cpp/hprose/echo-peer");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("RESULT Hprose C++ source SDK 中文"), output);
            }
        }
    }
}
