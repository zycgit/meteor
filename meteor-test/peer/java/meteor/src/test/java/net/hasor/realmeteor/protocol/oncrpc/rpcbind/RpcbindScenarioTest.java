/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc.rpcbind;
import java.time.Instant;
import java.util.Arrays;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.rpcbind.PortMapperMapping;
import net.hasor.meteor.protocol.oncrpc.rpcbind.RpcbindClient;
import net.hasor.meteor.protocol.oncrpc.rpcbind.RpcbindMapping;
import net.hasor.realmeteor.protocol.oncrpc.OncRpcNativeProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** 正式控制客户端经 bootstrap、Caller 和独立 libtirpc rpcbind 对端完成互通。 */
public class RpcbindScenarioTest {
    @Test(timeout = 30000)
    public void version2TcpDiscoversNativePort() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // v2 GETPORT 按显式 TCP 协议号查询。
            assertEquals(8123, rpcbind.getPort(536870913L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version2TcpDiscoversUdpMapping() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETPORT 的映射协议独立于本次控制调用的 TCP 通道。
            assertEquals(8124, rpcbind.getPort(536870913L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version2UdpDiscoversNativePort() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc-udp", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // UDP 控制调用也使用标准 v2 GETPORT。
            assertEquals(8124, rpcbind.getPort(536870913L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version2MissingProgramReturnsZero() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 未注册程序返回标准零端口，而不是伪造地址。
            assertEquals(0, rpcbind.getPort(536870999L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version2DumpRetainsBothNativeTransportMappings() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // DUMP 是 XDR 指针链表，两个传输映射都能还原。
            PortMapperMapping[] mappings = rpcbind.dumpPorts();
            assertEquals(2, mappings.length);
            assertTrue(Arrays.stream(mappings).anyMatch(item -> item.getProtocol() == 6 && item.getPort() == 8123));
            assertTrue(Arrays.stream(mappings).anyMatch(item -> item.getProtocol() == 17 && item.getPort() == 8124));
        }
    }

    @Test(timeout = 30000)
    public void version2ExplicitSetPublishesOnlyRequestedMapping() throws Exception {
        // rpcbind 变更要求本机目标；独立 C 容器进程共享 runner 网络，不计为独立网络互通。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startLocalRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 调用方显式选择控制动作，不由普通业务服务发布自动注册。
            assertTrue(rpcbind.setPort(new PortMapperMapping(536870914L, 1, 6, 9123)));
            assertEquals(9123, rpcbind.getPort(536870914L, 1, "tcp"));
            assertEquals(0, rpcbind.getPort(536870914L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version2ExplicitUnsetRemovesBothTransports() throws Exception {
        // rpcbind 变更要求本机目标；独立 C 容器进程共享 runner 网络，不计为独立网络互通。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startLocalRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // v2 UNSET 的标准作用范围是给定程序版本的全部传输。
            assertTrue(rpcbind.setPort(new PortMapperMapping(536870914L, 1, 6, 9123)));
            assertTrue(rpcbind.setPort(new PortMapperMapping(536870914L, 1, 17, 9124)));

            // 显式撤销后，两种传输均不能再发现。
            assertTrue(rpcbind.unsetPort(536870914L, 1));
            assertEquals(0, rpcbind.getPort(536870914L, 1, "tcp"));
            assertEquals(0, rpcbind.getPort(536870914L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version3TcpDiscoversNativeUniversalAddress() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETADDR 按真实 TCP 控制通道选择 netid。
            assertEquals("127.0.0.1.31.187", rpcbind.getAddress(536870913L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version3UdpDiscoversNativeUniversalAddress() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc-udp", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // UDP 控制通道查询 UDP universal address。
            assertEquals("127.0.0.1.31.188", rpcbind.getAddress(536870913L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version3GetAddressAllowsStandardVersionFallback() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETADDR 允许命中同程序的其它版本，精确版本查询须使用 v4 GETVERSADDR。
            assertEquals("127.0.0.1.31.187", rpcbind.getAddress(536870913L, 2, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version3MissingProgramReturnsNull() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // daemon 的空结果在公开控制 API 中表现为 null。
            assertNull(rpcbind.getAddress(536870999L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version3DumpRetainsNativeUniversalAddressAndOwner() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // DUMP 还原每个 rpcb 节点，包含 transport、address 与 owner。
            RpcbindMapping[] mappings = rpcbind.dumpMappings();
            assertEquals(2, mappings.length);
            RpcbindMapping tcp = Arrays.stream(mappings) //
                    .filter(item -> StringUtils.equals("tcp", item.getNetid())) //
                    .findFirst().orElseThrow();
            assertEquals("127.0.0.1.31.187", tcp.getAddress());
            assertEquals("java-test", tcp.getOwner());
        }
    }

    @Test(timeout = 30000)
    public void version3ExplicitSetPublishesUniversalAddress() throws Exception {
        // rpcbind 变更要求本机目标；独立 C 容器进程共享 runner 网络，不计为独立网络互通。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startLocalRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // SET 的注册决定由独立 rpcbind daemon 返回。
            RpcbindMapping mapping = new RpcbindMapping(536870914L, 1, "tcp", "127.0.0.1.35.163", "java-test");
            assertTrue(rpcbind.setMapping(mapping));
            assertEquals("127.0.0.1.35.163", rpcbind.getAddress(536870914L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version3ExplicitUnsetPreservesOtherTransport() throws Exception {
        // rpcbind 变更要求本机目标；独立 C 容器进程共享 runner 网络，不计为独立网络互通。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startLocalRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 显式 netid 避免 UNSET 扩大到同程序的另一个传输。
            RpcbindMapping tcp = new RpcbindMapping(536870914L, 1, "tcp", "127.0.0.1.35.163", "java-test");
            RpcbindMapping udp = new RpcbindMapping(536870914L, 1, "udp", "127.0.0.1.35.164", "java-test");
            assertTrue(rpcbind.setMapping(tcp));
            assertTrue(rpcbind.setMapping(udp));

            // 只撤销 TCP，UDP 映射仍在 DUMP 中。
            assertTrue(rpcbind.unsetMapping(536870914L, 1, "tcp"));
            RpcbindMapping[] remaining = rpcbind.dumpMappings();
            boolean udpPresent = Arrays.stream(remaining).anyMatch(item -> {
                boolean matchingProgram = item.getProgram() == 536870914L;
                return matchingProgram && StringUtils.equals("udp", item.getNetid());
            });
            assertTrue(udpPresent);
            assertNull(rpcbind.getVersionAddress(536870914L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version3GetTimeUsesUnsignedEpochSeconds() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETTIME 是 daemon 的 Unix 时间，不是 Meteor 本地直接返回的时间。
            long now = Instant.now().getEpochSecond();
            assertTrue(Math.abs(rpcbind.getTime() - now) < 60);
        }
    }

    @Test(timeout = 30000)
    public void version4GetAddressRetainsFallback() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 版本 4 GETADDR 仍保留标准版本回退行为。
            assertEquals("127.0.0.1.31.187", rpcbind.getAddressV4(536870913L, 2, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version4TcpExactVersionDiscoversNativeAddress() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETVERSADDR 的程序版本匹配必须精确。
            assertEquals("127.0.0.1.31.187", rpcbind.getVersionAddress(536870913L, 1, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void version4UdpExactVersionDiscoversNativeAddress() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc-udp", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // v4 的精确查询同样支持独立 UDP rpcbind 调用。
            assertEquals("127.0.0.1.31.188", rpcbind.getVersionAddress(536870913L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void version4MissingExactVersionDoesNotFallback() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 已有 v1 不意味着精确查询 v2 能得到地址。
            assertNull(rpcbind.getVersionAddress(536870913L, 2, "tcp"));
        }
    }

    @Test(timeout = 30000)
    public void lookupRejectsNetidDifferentFromRpcTransport() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // GETADDR 的 r_netid 不能改变 daemon 按调用链路选出的传输类型。
            assertThrows(IllegalArgumentException.class, () -> rpcbind.getAddress(536870913L, 1, "udp"));
        }
    }

    @Test(timeout = 30000)
    public void secondControlClientReusesExistingSubscriptions() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // 同一个上下文只注册三个版本的消费者契约，不随控制对象数量膨胀。
            int count = client.getServiceIDs().size();
            RpcbindClient second = new RpcbindClient(client, target);
            assertEquals(8123, second.getPort(536870913L, 1, "tcp"));
            assertEquals(count, client.getServiceIDs().size());
        }
    }

    @Test(timeout = 30000)
    public void discoveredUniversalAddressBecomesOrdinaryMeteorTarget() throws Exception {
        // 用户先配置并启动 Meteor；rpcbind 对端是独立 libtirpc 进程。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.startRpcbind("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            client.start();
            int port = peer.awaitPort();
            InterAddress target = new InterAddress("oncrpc", peer.host(), port, client.getSettings().getUnitName());
            RpcbindClient rpcbind = new RpcbindClient(client, target);

            // rpcbind 只返回目标，业务连接仍由调用者通过普通 Meteor 地址调用。
            String universal = rpcbind.getVersionAddress(536870913L, 1, "tcp");
            InterAddress business = RpcbindClient.toAddress("oncrpc", universal, client.getSettings().getUnitName());
            assertEquals("127.0.0.1", business.getHost());
            assertEquals(8123, business.getPort());
        }
    }

}
