/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcException;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcMessage;
import org.junit.Test;
import static org.junit.Assert.*;

/** 公开入口上的 ONC RPC 普通过程、IDL 数据类型及认证策略。 */
public class OncRpcCallScenarioTest {
    @Test(timeout = 20000)
    public void tcpPreservesArgumentDeclarationOrder() throws Exception {
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
            assertEquals(1923, remote.add(19, 23));
        }
    }

    @Test(timeout = 20000)
    public void udpPreservesArgumentDeclarationOrder() throws Exception {
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
            assertEquals(1923, remote.add(19, 23));
        }
    }

    @Test(timeout = 20000)
    public void tcpVoidProcedureStillReceivesAReply() throws Exception {
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
            remote.ping();
            assertEquals("after-void", remote.echo("after-void"));
        }
    }

    @Test(timeout = 20000)
    public void udpVoidProcedureStillReceivesAReply() throws Exception {
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
            remote.ping();
            assertEquals("after-void", remote.echo("after-void"));
        }
    }

    @Test(timeout = 20000)
    public void tcpOpaquePreservesZeroAndUnsignedBytes() throws Exception {
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
            byte[] value = { 0, 1, -1, -128, 127 };
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 20000)
    public void udpOpaquePreservesZeroAndUnsignedBytes() throws Exception {
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
            byte[] value = { 0, 1, -1, -128, 127 };
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 20000)
    public void tcpEmptyOpaqueIsAnOrdinaryValue() throws Exception {
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
            assertArrayEquals(new byte[0], remote.bytes(new byte[0]));
        }
    }

    @Test(timeout = 20000)
    public void udpEmptyOpaqueIsAnOrdinaryValue() throws Exception {
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
            assertArrayEquals(new byte[0], remote.bytes(new byte[0]));
        }
    }

    @Test(timeout = 20000)
    public void tcpLargeOpaqueSpansNetworkReads() throws Exception {
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
            byte[] value = new byte[200000];
            Arrays.fill(value, (byte) 91);
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 20000)
    public void udpLargeOpaqueFitsOneDatagram() throws Exception {
        // 用户仅选择传输与协议，具体线格式由 ONC RPC 插件负责。
        // IPv6 本地回环避免开发机虚拟网卡对大 IPv4 UDP 报文的转发限制。
        int port;
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress("::1", 0))) {
            port = socket.getLocalPort();
        }
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        incoming.connector("udp").bind("::1", port).protocol("oncrpc-udp");
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
            byte[] value = new byte[50000];
            Arrays.fill(value, (byte) 71);
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 20000)
    public void tcpCustomStructPreservesAllIdlFields() throws Exception {
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
            OncRpcSample expected = sample();
            OncRpcSample actual = remote.mirror(expected);
            assertSample(expected, actual);
        }
    }

    @Test(timeout = 20000)
    public void udpCustomStructPreservesAllIdlFields() throws Exception {
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
            OncRpcSample expected = sample();
            OncRpcSample actual = remote.mirror(expected);
            assertSample(expected, actual);
        }
    }

    @Test(timeout = 20000)
    public void tcpDiscriminatedUnionSelectsIntegerArm() throws Exception {
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
            OncRpcSample expected = sample();
            expected.getChoice().setTag(1);
            expected.getChoice().setNumber(-97);
            OncRpcSample actual = remote.mirror(expected);
            assertEquals(1, actual.getChoice().getTag());
            assertEquals(-97, actual.getChoice().getNumber());
        }
    }

    @Test(timeout = 20000)
    public void udpDiscriminatedUnionSelectsDefaultVoidArm() throws Exception {
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
            OncRpcSample expected = sample();
            expected.getChoice().setTag(99);
            OncRpcSample actual = remote.mirror(expected);
            assertEquals(99, actual.getChoice().getTag());
            assertNull(actual.getChoice().getText());
        }
    }

    @Test(timeout = 20000)
    public void tcpOptionalPointerCanBeAbsent() throws Exception {
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
            OncRpcSample expected = sample();
            expected.setOptional(null);
            assertNull(remote.mirror(expected).getOptional());
        }
    }

    @Test(timeout = 20000)
    public void udpOptionalPointerPreservesRecursiveValues() throws Exception {
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
            OncRpcSample expected = sample();
            OncRpcOptional nested = new OncRpcOptional();
            nested.setValue(64);
            expected.getOptional().setNext(nested);
            OncRpcOptional actual = remote.mirror(expected).getOptional();
            assertEquals(32, actual.getValue());
            assertEquals(64, actual.getNext().getValue());
            assertNull(actual.getNext().getNext());
        }
    }

    @Test(timeout = 15000)
    public void tcpBusinessFailureUsesStandardSystemError() throws Exception {
        // ONC RPC 的标准异常臂不携带任意 Java 异常对象。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 普通业务失败映射为 SYSTEM_ERR，由公开 Future 返回调用方。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                client.getRsfClient(server.getListenAddress("oncrpc")) //
                        .syncInvoke(service, "crash", new Class<?>[0], new Object[0]);
            });
            OncRpcException standard = findStandardFailure(failure);
            assertEquals(OncRpcMessage.SYSTEM_ERR, standard.getAcceptStatus());
        }
    }

    @Test(timeout = 15000)
    public void udpBusinessFailureUsesStandardSystemError() throws Exception {
        // ONC RPC 的标准异常臂不携带任意 Java 异常对象。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 普通业务失败映射为 SYSTEM_ERR，由公开 Future 返回调用方。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                client.getRsfClient(server.getListenAddress("oncrpc-udp")) //
                        .syncInvoke(service, "crash", new Class<?>[0], new Object[0]);
            });
            OncRpcException standard = findStandardFailure(failure);
            assertEquals(OncRpcMessage.SYSTEM_ERR, standard.getAcceptStatus());
        }
    }

    @Test(timeout = 15000)
    public void authSysIdentityReachesPublishedServiceFilter() throws Exception {
        // AUTH_SYS 是调用方自报的身份数据，服务端过滤器可以读取其协议选项。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("oncrpc");
        outgoing.connector("tcp").protocol("oncrpc") //
                .option("auth", "sys").option("authUid", 1234).option("authGid", 5678) //
                .option("authGroups", "11,22").option("authMachine", "example-host");
        AtomicReference<String> uid = new AtomicReference<>();
        AtomicReference<String> machine = new AtomicReference<>();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().bindFilter("capture-auth", (request, response, chain) -> {
                if (!request.isLocal()) {
                    uid.set(request.getOption("oncrpc.auth.uid"));
                    machine.set(request.getOption("oncrpc.auth.machine"));
                }
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 认证字段经过正常请求链，而不是在测试中直接读取 Mapper 状态。
            OncRpcDemoService remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);
            assertEquals("identity", remote.echo("identity"));
            assertEquals("1234", uid.get());
            assertEquals("example-host", machine.get());
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

    private static OncRpcSample sample() {
        OncRpcSample value = new OncRpcSample();
        value.setSignedValue(Integer.MIN_VALUE);
        value.setUnsignedValue(0xffffffffL);
        value.setSignedHyper(Long.MIN_VALUE + 7);
        value.setUnsignedHyper(new BigInteger("18446744073709551615"));
        value.setFloatValue(-3.25f);
        value.setDoubleValue(7.125d);
        value.setBoolValue(true);
        value.setText("XDR sample");
        value.setFixedOpaque(new byte[] { 1, 2, -1, 4, 5 });
        value.setBytes(new byte[] { 0, -1, 127 });
        value.setFixedNumbers(Arrays.asList(-1, 0, 1));
        value.setNumbers(Arrays.asList(7, 9, 11, 13));
        value.setColour(5);
        OncRpcChoice choice = new OncRpcChoice();
        choice.setTag(2);
        choice.setText("union text");
        value.setChoice(choice);
        OncRpcOptional optional = new OncRpcOptional();
        optional.setValue(32);
        value.setOptional(optional);
        return value;
    }

    private static void assertSample(OncRpcSample expected, OncRpcSample actual) {
        assertEquals(expected.getSignedValue(), actual.getSignedValue());
        assertEquals(expected.getUnsignedValue(), actual.getUnsignedValue());
        assertEquals(expected.getSignedHyper(), actual.getSignedHyper());
        assertEquals(expected.getUnsignedHyper(), actual.getUnsignedHyper());
        assertEquals(expected.getFloatValue(), actual.getFloatValue(), 0);
        assertEquals(expected.getDoubleValue(), actual.getDoubleValue(), 0);
        assertEquals(expected.getBoolValue(), actual.getBoolValue());
        assertEquals(expected.getText(), actual.getText());
        assertArrayEquals(expected.getFixedOpaque(), actual.getFixedOpaque());
        assertArrayEquals(expected.getBytes(), actual.getBytes());
        assertEquals(expected.getFixedNumbers(), actual.getFixedNumbers());
        assertEquals(expected.getNumbers(), actual.getNumbers());
        assertEquals(expected.getColour(), actual.getColour());
        assertEquals(expected.getChoice().getTag(), actual.getChoice().getTag());
        assertEquals(expected.getChoice().getText(), actual.getChoice().getText());
        assertEquals(expected.getOptional().getValue(), actual.getOptional().getValue());
    }

    private static OncRpcException findStandardFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OncRpcException standard) {
                return standard;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected ONC RPC standard failure", failure);
    }
}
