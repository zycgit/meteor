/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 经典 Dubbo Node SDK 通过正式直连 API 调用 Meteor，不依赖注册中心。 */
public class NodeDubboConsumerScenarioTest {
    @Test(timeout = 60000)
    public void nodeSdkCallsMeteorStringEchoDirectly() throws Exception {
        // bootstrap 启动当前场景独占的 Dubbo TCP 监听，随后发布普通 Java 接口。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("node-dubbo-basic-provider");
        configuration.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", DubboInteropService.class.getName()) //
                    .group("interop").version("1.0.0").serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();
            String target = "dubbo://" + ContainerProcess.runnerHost() + ":" + port;

            // 锁定版 dubbo2.js 在 Node 容器内独立运行，由 SDK 负责 Hessian 与 Dubbo 报文。
            List<String> command = List.of("node", "/opt/meteor-peer/node/dubbo/consumer.cjs");
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, target)) {
                JsonNode result = consumer.awaitResult();
                assertEquals("Node Dubbo 中文 🙂", result.required("value").textValue());
            }
        }
    }
}
