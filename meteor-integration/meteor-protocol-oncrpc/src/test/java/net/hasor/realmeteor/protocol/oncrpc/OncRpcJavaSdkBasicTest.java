/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.acplt.oncrpc.OncRpcClient;
import org.acplt.oncrpc.OncRpcTcpClient;
import org.acplt.oncrpc.OncRpcUdpClient;
import org.acplt.oncrpc.XdrString;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 本插件基础验证：同一 JUnit 进程内，Meteor 与 RemoteTea SDK 经 TCP、UDP 双向真实调用。 */
public class OncRpcJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void remoteTeaJavaClientCallsMeteorOverTcp() throws Exception {
        // 使用插件本地接口和 IDL 编码资源，标准 XDR string 保持 ASCII。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 当前进程直接创建 SDK 客户端，连接及 RPC/XDR 处理全部由 RemoteTea 持有。
            int port = server.getListenAddress("oncrpc").getPort();
            InetAddress host = InetAddress.getByName("127.0.0.1");
            OncRpcClient remote = new OncRpcTcpClient(host, 0x31234567, 1, port);
            try {
                remote.setTimeout(5000);
                remote.setCharacterEncoding(StandardCharsets.US_ASCII.name());
                String expected = "RemoteTea TCP to Meteor";
                XdrString result = new XdrString();
                remote.call(1, new XdrString(expected), result);
                assertEquals(expected, result.stringValue());
            } finally {
                remote.close();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void remoteTeaJavaClientCallsMeteorOverUdp() throws Exception {
        // 沿用本插件 UDP 场景的随机端口准备方式，不要求系统 rpcbind。
        int port;
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").bind("127.0.0.1", port).protocol("oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // SDK 自己维护事务号和 UDP 重传，不经 Meteor 消费端发起调用。
            int actualPort = server.getListenAddress("oncrpc-udp").getPort();
            InetAddress host = InetAddress.getByName("127.0.0.1");
            OncRpcClient remote = new OncRpcUdpClient(host, 0x31234567, 1, actualPort);
            try {
                remote.setTimeout(5000);
                remote.setCharacterEncoding(StandardCharsets.US_ASCII.name());
                String expected = "RemoteTea UDP to Meteor";
                XdrString result = new XdrString();
                remote.call(1, new XdrString(expected), result);
                assertEquals(expected, result.stringValue());
            } finally {
                remote.close();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsRemoteTeaJavaServerOverTcp() throws Exception {
        // SDK 直接监听随机 TCP 端口，Meteor 只配置消费者，不发布本地实现。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.setDefaultTimeout(5000);
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcSdkServer server = OncRpcSdkServer.tcp(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // SDK 分派添加响应前缀，证明调用经过真实 RemoteTea 网络服务。
            String unit = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", server.getPort(), unit);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("remotetea-sdk:Meteor TCP", remote.echo("Meteor TCP"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsRemoteTeaJavaServerOverUdp() throws Exception {
        // 同进程 SDK UDP transport 完成监听和 RPC/XDR，Meteor 不持有服务实现。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setDefaultTimeout(5000);
        configuration.connector("udp").protocol("oncrpc-udp");
        try (OncRpcSdkServer server = OncRpcSdkServer.udp(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 直连 SDK 已绑定的真实端口，响应前缀不能由本地调用捷径产生。
            String unit = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", server.getPort(), unit);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("remotetea-sdk:Meteor UDP", remote.echo("Meteor UDP"));
        }
    }
}
