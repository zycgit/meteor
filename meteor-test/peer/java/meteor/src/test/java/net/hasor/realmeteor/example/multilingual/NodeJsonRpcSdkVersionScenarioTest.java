/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropService;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;

/** 各主要 Jayson 版本分别使用自己的 HTTP 客户端和服务端进行真实双向调用。 */
@RunWith(Parameterized.class)
public class NodeJsonRpcSdkVersionScenarioTest {
    private final String profile;
    private final String direction;

    public NodeJsonRpcSdkVersionScenarioTest(String sdk, String version, String profile, String direction) {
        this.profile = profile;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/node/jsonrpc/{0}@{1}/{2}/{3}")
    public static List<Object[]> versions() throws Exception {
        return NodeRpcPeer.compatibilityCases("jsonrpc");
    }

    @Test(timeout = 60000)
    public void selectedSdkDirectionPreservesUnicodeArguments() throws Exception {
        // 每个参数化实例只执行清单声明的一个方向，不修改其它场景的 SDK 选择。
        if (StringUtils.equals("SDK-to-Meteor", this.direction)) {
            this.sdkCallsMeteor();
        } else {
            this.meteorCallsSdk();
        }
    }

    private void sdkCallsMeteor() throws Exception {
        // 从公开 bootstrap 启动当前协议，再发布命名服务。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer").protocol("jsonrpc").serialize("Json").register();

            // 本版本 Jayson 生成位置参数和字符串请求 ID，并校验 Unicode 响应。
            int port = provider.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "client", target, "positional")) {
                peer.requireSuccess();
            }
        }
    }

    private void meteorCallsSdk() throws Exception {
        // 对端使用当前版本 Jayson 的服务分派和 HTTP 监听，独占自己的临时端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").protocol("jsonrpc").contextPath("/rpc");
        try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "server", "http"); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            MetBindInfo<JsonRpcInteropService> service = consumer.publisher().rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer").protocol("jsonrpc").serialize("Json").register();

            // 普通 Meteor 代理直接访问 SDK，协议与业务返回值均不能由测试器代转。
            InterAddress target = new InterAddress("jsonrpc", peer.host(), port, "default");
            JsonRpcInteropService remote = consumer.getRsfClient(target).getRemote(service);
            String message = "Meteor → Jayson 中文🙂 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
