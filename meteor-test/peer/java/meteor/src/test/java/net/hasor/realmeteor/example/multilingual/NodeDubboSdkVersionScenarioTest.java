/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** dubbo2.js 1.x 与 2.x 的真实 SDK 消费者分别调用 Meteor 提供者。 */
@RunWith(Parameterized.class)
public class NodeDubboSdkVersionScenarioTest {
    private final String profile;

    public NodeDubboSdkVersionScenarioTest(String sdk, String version, String profile, String direction) {
        this.profile = profile;
    }

    @Parameterized.Parameters(name = "sdk/node/dubbo/{0}@{1}/{2}/{3}")
    public static List<Object[]> versions() throws Exception {
        return NodeRpcPeer.compatibilityCases("dubbo");
    }

    @Test(timeout = 60000)
    public void selectedSdkConsumerPreservesUnicodeArguments() throws Exception {
        // 公开 bootstrap 先启动真实 TCP 监听，再发布现有普通接口及业务实现。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("node-dubbo-version-provider");
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("dubbo");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", DubboInteropService.class.getName()) //
                    .group("interop").version("1.0.0").serialize("Hessian2").register();

            // 当前版本 SDK 独立进程执行 String 调用，并精确验证 Unicode 返回值。
            int port = provider.getListenAddress("dubbo").getPort();
            String target = "dubbo://" + ContainerProcess.runnerHost() + ":" + port;
            String message = "Node Dubbo → Meteor 中文🙂 <&>";
            try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "consumer", target, message)) {
                peer.requireSuccess();
            }
        }
    }
}
