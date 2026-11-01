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
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 插件公开启动入口的基础发布与调用验证。 */
public class OncRpcBootstrapScenarioTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void tcpEchoUsesPublishedJavaInterface() throws Exception {
        // 用户仅选择传输与协议，具体线格式由 ONC RPC 插件负责。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅带有 IDL 编码资源的普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            OncRpcDemoService remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);

            // 通过普通代理发起真实网络 RPC，验证本场景对应的数据契约。
            String result = remote.echo("Hello ONC RPC");
            assertEquals("Hello ONC RPC", result);
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void udpEchoUsesPublishedJavaInterface() throws Exception {
        // 用户仅选择传输与协议，具体线格式由 ONC RPC 插件负责。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布和订阅带有 IDL 编码资源的普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            OncRpcDemoService remote = client.getRsfClient(server.getListenAddress("oncrpc-udp")).getRemote(service);

            // 通过普通代理发起真实网络 RPC，验证本场景对应的数据契约。
            String result = remote.echo("Hello ONC RPC UDP");
            assertEquals("Hello ONC RPC UDP", result);
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void rejectedOncRpcContractKeepsLocalCallsAvailable() throws Exception {
        // 普通 Java 接口没有 ONC RPC 契约，但仍可通过本地优先调用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsf");
        configuration.connector("tcp").protocol("oncrpc");
        configuration.connector("tcp").protocol("rsf");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class, calls::incrementAndGet) //
                    .protocol("oncrpc", "rsf").register();

            // 接口依然保留在公开服务查询中，本地调用可以继续执行业务实现。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            context.getRsfClient().getRemote(service).run();
            assertEquals(1, calls.get());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void rejectedOncRpcContractKeepsRsfNetworkCallsAvailable() throws Exception {
        // 服务端同时装配 ONC RPC 和 RSF，只有 RSF 对外监听。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf");
        incoming.connector("tcp").protocol("oncrpc");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf");
        outgoing.connector("tcp").protocol("rsf");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<Runnable> provider = server.publisher().rsfService(Runnable.class, calls::incrementAndGet) //
                    .protocol("oncrpc", "rsf").register();
            MetBindInfo<Runnable> consumer = client.publisher().rsfService(Runnable.class) //
                    .protocol("rsf").asDistributedCall().register();

            // 服务在公开查询中可见，另一个应用仍能通过 RSF 网络通道调用。
            MetBindInfo<?> registered = server.getServiceInfo(provider.getBindID());
            assertNotNull(registered);
            assertEquals(provider.getBindID(), registered.getBindID());
            client.getRsfClient(server.getListenAddress("rsf")).getRemote(consumer).run();
            assertEquals(1, calls.get());
        }
    }

    private MetConfiguration incoming(String transport) throws Exception {
        String protocol = "oncrpc";
        int port = 0;
        if (StringUtils.equals("udp", transport)) {
            protocol = "oncrpc-udp";
            try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.connector(transport).bind("127.0.0.1", port).protocol(protocol);
        return configuration;
    }

    private MetConfiguration outgoing(String transport) {
        String protocol = "oncrpc";
        if (StringUtils.equals("udp", transport)) {
            protocol = "oncrpc-udp";
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.connector(transport).protocol(protocol);
        return configuration;
    }
}
