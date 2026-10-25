/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.net.URI;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 固定源码版本的 Triton ONC RPC SDK 与 Meteor 通过 TCP 双向调用标准 ASCII string。 */
public class OncRpcNodeInteropTest {
    @Test(timeout = 60000)
    public void nodeSdkConsumerCallsMeteorProvider() throws Exception {
        // Meteor 经公开 bootstrap 监听 TCP，绑定测试自有的标准 ONC RPC 程序与过程编号。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("oncrpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            int port = provider.getListenAddress("oncrpc").getPort();
            String address = "tcp://" + ContainerProcess.runnerHost() + ":" + port;
            String expected = "Node SDK to Meteor";
            String script = "/opt/meteor-peer/node/oncrpc/peer.js";
            List<String> command = List.of("node", script, "consumer", address, expected);

            // 独立 Node 进程由 SDK 创建真实连接、编码请求并解析 RPC 回复。
            try (OrderPeerProcess consumer = OrderPeerProcess.start("node", command, null)) {
                JsonNode result = consumer.awaitResult();
                assertEquals(expected, result.required("value").textValue());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorConsumerCallsNodeSdkProvider() throws Exception {
        // Node 使用 SDK 的 RpcServer 独立发布过程，动态端口与其它测试隔离。
        String script = "/opt/meteor-peer/node/oncrpc/peer.js";
        List<String> command = List.of("node", script, "provider");
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OrderPeerProcess provider = OrderPeerProcess.start("node", command, null); //
             MetContext consumer = configuration.buildContext()) {
            URI target = URI.create(provider.awaitReady("ONC RPC provider is ready: "));
            consumer.start();
            MetBindInfo<OncRpcDemoService> service = consumer.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc", target.getHost(), target.getPort(), "default");
            OncRpcDemoService remote = consumer.getRsfClient(address).getRemote(service);

            // 普通公开代理的返回值必须来自独立 SDK 提供者，不通过本地优先或协议转发。
            String expected = "Meteor to Node SDK";
            assertEquals(expected, remote.echo(expected));
        }
    }
}
