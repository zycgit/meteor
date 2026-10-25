/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.oncrpc.python;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonSdkPeerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 开发中的 sunrpc 1.1.0 经 TCP 双向调用；只验证 ASCII string 普通过程。 */
public class OncRpcPythonInteropTest {

    @Test(timeout = 45000)
    public void pythonSunrpcCallsMeteor() throws Exception {
        // 从公开 bootstrap 创建 ONC RPC 监听，复用现有业务过程和协议编号。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("oncrpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // Python SDK 直接连接 Meteor，独立完成请求编码、返回解码和字符串断言。
            int port = provider.getListenAddress("oncrpc").getPort();
            String host = ContainerProcess.runnerHost();
            try (PythonSdkPeerProcess peer = PythonSdkPeerProcess.start("oncrpc", "consumer", //
                    "--host", host, "--port", Integer.toString(port))) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorCallsPythonSunrpc() throws Exception {
        // Python SDK 发布程序 0x31234567、版本 1、过程 1，不使用模拟报文服务端。
        try (PythonSdkPeerProcess provider = PythonSdkPeerProcess.start("oncrpc", "provider")) {
            int port = provider.awaitPort();
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("tcp").protocol("oncrpc");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OncRpcDemoService> service = consumer.publisher().rsfService(OncRpcDemoService.class) //
                        .protocol("oncrpc").bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

                // 只有订阅、没有本地业务实现，调用必须经过真实 TCP 和 SDK 分派。
                InterAddress address = new InterAddress("oncrpc", provider.host(), port, "default");
                OncRpcDemoService remote = consumer.getRsfClient(address).getRemote(service);
                assertEquals("Meteor to Python sunrpc", remote.echo("Meteor to Python sunrpc"));
            }
        }
    }
}
