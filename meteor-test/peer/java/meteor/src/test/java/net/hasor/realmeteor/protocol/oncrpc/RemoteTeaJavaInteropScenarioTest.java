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
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;

/** RemoteTea Java SDK 经真实 TCP、UDP 调用 Meteor 提供者，不依赖跨语言对端。 */
public class RemoteTeaJavaInteropScenarioTest {
    @Test(timeout = 30000)
    public void remoteTeaJavaTcpClientCallsMeteor() throws Exception {
        // Meteor 通过公开入口监听 TCP；普通业务接口绑定既有 ONC RPC 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 独立 JVM 中的 RemoteTea 客户端使用真实端口，遵守 IDL 的标准 ASCII string。
            int port = server.getListenAddress("oncrpc").getPort();
            String expected = "RemoteTea TCP to Meteor";
            try (RemoteTeaPeerProcess peer = RemoteTeaPeerProcess.start("tcp", port, expected, "echo")) {
                peer.requireResult(expected);
            }
        }
    }

    @Test(timeout = 30000)
    public void remoteTeaJavaUdpClientCallsMeteor() throws Exception {
        // 沿用 UDP 基础场景的随机端口准备方式，避免给对端传递尚未确定的零端口。
        int port;
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").bind(ContainerProcess.runnerHost(), port).protocol("oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // UDP RPC 的事务号、重传和 XDR 均由 RemoteTea 处理，测试不手写报文。
            int actualPort = server.getListenAddress("oncrpc-udp").getPort();
            String expected = "RemoteTea UDP to Meteor";
            try (RemoteTeaPeerProcess peer = RemoteTeaPeerProcess.start("udp", actualPort, expected, "echo")) {
                peer.requireResult(expected);
            }
        }
    }

    @Test(timeout = 30000)
    public void remoteTeaJavaTcpClientReceivesGarbageArgumentsForNonAsciiString() throws Exception {
        // 同一标准 IDL 没有约定 UTF-8 扩展，Meteor 应拒绝非 ASCII 参数而不是静默替换。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // RemoteTea 真正发送 UTF-8 字节，必须由 SDK 解出远端的 GARBAGE_ARGS 响应。
            int port = server.getListenAddress("oncrpc").getPort();
            try (RemoteTeaPeerProcess peer = RemoteTeaPeerProcess.start("tcp", port, "中文🙂", "reject-non-ascii")) {
                peer.requireArgumentRejection();
            }
        }
    }

    @Test(timeout = 30000)
    public void remoteTeaJavaUdpClientReceivesGarbageArgumentsForNonAsciiString() throws Exception {
        // 保留标准 ASCII string 契约，UDP 同样不能隐式放宽为 UTF-8 扩展。
        int port;
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").bind(ContainerProcess.runnerHost(), port).protocol("oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 只有对应事务的参数拒绝才算成功；UDP 超时或发送失败都必须让测试失败。
            int actualPort = server.getListenAddress("oncrpc-udp").getPort();
            try (RemoteTeaPeerProcess peer = RemoteTeaPeerProcess.start("udp", actualPort, "中文🙂", "reject-non-ascii")) {
                peer.requireArgumentRejection();
            }
        }
    }
}
