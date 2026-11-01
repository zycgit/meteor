/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 未修改的 hessiancpp 1.1.0 源码 SDK 通过 HTTP 调用 Meteor 的基础场景。 */
@NxnRpc(name = "hessian", version = "1")
public class HessianCppSourceSdkScenarioTest {

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sourceCppSdkConsumerCallsMeteorHessianProvider() throws Exception {
        // 从 bootstrap 启动 Hessian 1 端点，普通 Java 接口作为真实服务提供者。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hessian").contextPath("/hessian").option("version", 1);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();

            // 独立 C++ SDK 自行完成 HTTP、Hessian 请求编码及结果解码。
            int port = provider.getListenAddress("hessian").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/hessian/demo";
            String message = "hessiancpp -> Meteor";
            String executable = "/opt/meteor-peer/native/hessian/cpp/consumer";
            List<String> command = List.of(executable, message);
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("OK hessiancpp: " + message), output);
            }
        }
    }
}
