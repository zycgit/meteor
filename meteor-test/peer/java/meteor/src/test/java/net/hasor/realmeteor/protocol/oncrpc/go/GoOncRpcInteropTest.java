/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.oncrpc.go;
import net.hasor.meteor.test.nxn.NxnRpc;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 固定 SunRPC 源码版本与 Meteor 通过现有 ONC echo 契约验证基础 TCP 双向调用。 */
@NxnRpc(name = "oncrpc", version = "2")
public class GoOncRpcInteropTest {
    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void goSunRpcConsumerCallsMeteorProvider() throws Exception {
        // 从正式入口启动 Meteor，只发布既有 interop.x 对应的 ONC 程序。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("oncrpc");
        OncRpcDemoService implementation = new OncRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                return value + "|meteor";
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String target = server.getListenAddress("oncrpc").getHostPort();

            // Go 仅使用上游 ClientCodec 发送请求，服务实现产生的后缀证明真实执行。
            try (GoOncRpcPeer peer = GoOncRpcPeer.start("client", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.path("ok").asBoolean(), result.toString());
                assertEquals("Go SunRPC to Meteor|meteor", result.path("message").asText());
            }
        }
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsGoSunRpcProvider() throws Exception {
        // Go ServerCodec 提供程序 0x31234567、版本 1、过程 1；Meteor 只创建出站端点。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.setDefaultTimeout(10000);
        configuration.connector("tcp").protocol("oncrpc");
        try (GoOncRpcPeer peer = GoOncRpcPeer.start("server", null); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc", peer.host(), port, "default");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // XDR string 使用 ASCII；Go 业务结果与 Meteor 实现明确区分。
            String request = "Meteor to Go SunRPC";
            assertEquals(request + "|go-oncrpc", remote.echo(request));
        }
    }
}
