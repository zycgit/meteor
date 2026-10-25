/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.example.protocol.hessian.HessianInteropDemoImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Node Hessian HTTP RPC SDK 通过正式代理调用 Meteor，仅验证基础 String 请求与响应。 */
public class NodeHessianConsumerScenarioTest {
    @Test(timeout = 60000)
    public void nodeSdkCallsMeteorStringEcho() throws Exception {
        // bootstrap 启动本场景的 HTTP 监听，再发布普通 Java 接口和 Hessian 服务路径。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("node-hessian-basic-provider");
        configuration.connector("http").bind("0.0.0.0", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", 2);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/hessian/demo";

            // 独立 Node SDK 进程承担完整 HTTP RPC，不用编码库和测试器代替客户端。
            List<String> command = List.of("node", "/opt/meteor-peer/node/hessian/consumer.cjs");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, target)) {
                JsonNode result = consumer.awaitResult();
                assertEquals("Node Hessian", result.required("value").textValue());
            }
        }
    }
}
