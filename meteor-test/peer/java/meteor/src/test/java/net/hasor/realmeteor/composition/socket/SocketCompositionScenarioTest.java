/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.socket;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.example.composition.socket.SocketEchoService;
import net.hasor.example.composition.thrift.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 所有插件同时存在时，二进制协议按连接独立探测和分派。 */
@NxnRpc(name = "rsf", version = "1")
@NxnRpc(name = "dubbo")
@NxnRpc(name = "thrift")
@NxnRpc(name = "oncrpc", version = "2")
public class SocketCompositionScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void concurrentProtocolsShareOneTcpListenerWithoutCrossedResponses() throws Exception {
        // 一个 TCP 端点挂载四个协议；客户端也一次性准备四条协议分支。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(15000);
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("rsf");
        listener.protocol("dubbo");
        listener.protocol("thrift");
        listener.protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(15000);
        ConnectorSettings connector = outgoing.connector("tcp");
        connector.protocol("rsf");
        connector.protocol("dubbo");
        connector.protocol("thrift");
        connector.protocol("oncrpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动后发布有区别的业务结果，防止错误路由仍然返回相同内容。
            server.start();
            client.start();
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsf").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubbo").protocol("dubbo").register();
            server.publisher().rsfService(EchoService.Iface.class, value -> "thrift:" + value) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsf").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubbo").protocol("dubbo").register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> oncrpc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();

            // 四个监听地址共享物理端口，交错提交请求后再等待结果。
            List<String> protocols = new ArrayList<>();
            List<MetBindInfo<?>> services = new ArrayList<>();
            // 下标 0：RSF 协议与对应订阅绑定。
            protocols.add("rsf");
            services.add(rsf);
            // 下标 1：Dubbo 协议与对应订阅绑定。
            protocols.add("dubbo");
            services.add(dubbo);
            // 下标 2：Thrift 协议与对应订阅绑定。
            protocols.add("thrift");
            services.add(thrift);
            // 下标 3：ONC RPC 协议与对应订阅绑定。
            protocols.add("oncrpc");
            services.add(oncrpc);
            protocols = List.copyOf(protocols);
            services = List.copyOf(services);
            int port = server.getListenAddress("rsf").getPort();
            List<MetFuture> pending = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int sequence = 0; sequence < 16; sequence++) {
                for (int protocol = 0; protocol < protocols.size(); protocol++) {
                    String name = protocols.get(protocol);
                    InterAddress address = server.getListenAddress(name);
                    assertEquals(port, address.getPort());
                    MetClient remote = client.getRsfClient(address);
                    String value = "call-" + sequence;
                    pending.add(remote.asyncInvoke(services.get(protocol), "echo", //
                            new Class<?>[] { String.class }, new Object[] { value }));
                    expected.add(name + ":" + value);
                }
            }
            for (int index = 0; index < pending.size(); index++) {
                assertEquals(expected.get(index), pending.get(index).getData(8, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void rejectedProbeConnectionDoesNotPoisonSharedTcpListener() throws Exception {
        // 一个 TCP 端点挂载四个协议；客户端也一次性准备四条协议分支。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(15000);
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("rsf");
        listener.protocol("dubbo");
        listener.protocol("thrift");
        listener.protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(15000);
        ConnectorSettings connector = outgoing.connector("tcp");
        connector.protocol("rsf");
        connector.protocol("dubbo");
        connector.protocol("thrift");
        connector.protocol("oncrpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动后发布有区别的业务结果，防止错误路由仍然返回相同内容。
            server.start();
            client.start();
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsf").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubbo").protocol("dubbo").register();
            server.publisher().rsfService(EchoService.Iface.class, value -> "thrift:" + value) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsf").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubbo").protocol("dubbo").register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> oncrpc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();

            // 所有协议均拒绝这个连接；等待实际 EOF，不用固定休眠猜测关闭。
            InterAddress address = server.getListenAddress("rsf");
            byte[] invalidPrefix = { 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f };
            try (Socket invalid = new Socket("127.0.0.1", address.getPort())) {
                invalid.setSoTimeout(5000);
                invalid.getOutputStream().write(invalidPrefix);
                invalid.getOutputStream().flush();
                assertEquals(-1, invalid.getInputStream().read());
            }

            // 拒绝仅作用于坏连接，四个后续真实请求仍分别到达自己的提供者。
            SocketEchoService rsfRemote = client.getRsfClient(address).getRemote(rsf);
            InterAddress dubboAddress = server.getListenAddress("dubbo");
            SocketEchoService dubboRemote = client.getRsfClient(dubboAddress).getRemote(dubbo);
            MetClient thriftRemote = client.getRsfClient(server.getListenAddress("thrift"));
            InterAddress oncAddress = server.getListenAddress("oncrpc");
            SocketEchoService oncRemote = client.getRsfClient(oncAddress).getRemote(oncrpc);
            assertEquals("rsf:alive", rsfRemote.echo("alive"));
            assertEquals("dubbo:alive", dubboRemote.echo("alive"));
            MetFuture thriftReply = thriftRemote.asyncInvoke(thrift, "echo", //
                    new Class<?>[] { String.class }, new Object[] { "alive" });
            assertEquals("thrift:alive", thriftReply.getData(5, TimeUnit.SECONDS));
            assertEquals("oncrpc:alive", oncRemote.echo("alive"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellingThriftCallDoesNotCancelOtherProtocolCalls() throws Exception {
        // 一个 TCP 端点挂载四个协议；客户端也一次性准备四条协议分支。
        // 两个业务线程允许其它协议在 Thrift 仍被 latch 阻塞时完成。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf") //
                .setDefaultTimeout(15000).setQueueMinPoolSize(2);
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("rsf");
        listener.protocol("dubbo");
        listener.protocol("thrift");
        listener.protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(15000);
        ConnectorSettings connector = outgoing.connector("tcp");
        connector.protocol("rsf");
        connector.protocol("dubbo");
        connector.protocol("thrift");
        connector.protocol("oncrpc");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动后发布有区别的业务结果，防止错误路由仍然返回相同内容。
            server.start();
            client.start();
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsf").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubbo").protocol("dubbo").register();
            server.publisher().rsfService(EchoService.Iface.class, value -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new TException("composition call was not released");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new TException(failure);
                        }
                        return "thrift:" + value;
                    }) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsf").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubbo").protocol("dubbo").register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> oncrpc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();

            // 等远端进入 Thrift 业务后取消，只撤销这个请求的等待。
            MetClient thriftRemote = client.getRsfClient(server.getListenAddress("thrift"));
            Class<?>[] types = { String.class };
            MetFuture cancelled = thriftRemote.asyncInvoke(thrift, "echo", types, new Object[] { "cancelled" });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());

                // 同一个服务端的其它协议继续完成请求，不受该请求取消影响。
                InterAddress rsfAddress = server.getListenAddress("rsf");
                InterAddress dubboAddress = server.getListenAddress("dubbo");
                InterAddress oncAddress = server.getListenAddress("oncrpc");
                Object[] arguments = { "surviving" };
                MetFuture rsfReply = client.getRsfClient(rsfAddress).asyncInvoke(rsf, "echo", types, arguments);
                MetFuture dubboReply = client.getRsfClient(dubboAddress).asyncInvoke(dubbo, "echo", types, arguments);
                MetFuture oncReply = client.getRsfClient(oncAddress).asyncInvoke(oncrpc, "echo", types, arguments);

                // 五秒内完成幸存调用，不能依赖 Thrift 业务十秒保护超时后才腾出线程。
                assertEquals("rsf:surviving", rsfReply.getData(5, TimeUnit.SECONDS));
                assertEquals("dubbo:surviving", dubboReply.getData(5, TimeUnit.SECONDS));
                assertEquals("oncrpc:surviving", oncReply.getData(5, TimeUnit.SECONDS));
                assertTrue(cancelled.isCancelled());
            } finally {
                release.countDown();
            }

            // 释放后的迟到响应不能污染同协议后续请求。
            MetFuture following = thriftRemote.asyncInvoke(thrift, "echo", types, new Object[] { "following" });
            assertEquals("thrift:following", following.getData(5, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @NxnRpc(name = "rsf", version = "1")
    @NxnRpc(name = "oncrpc", version = "2")
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void tcpAndUdpEndpointsRemainIndependentWithinOneRuntime() throws Exception {
        // RSF 只提供 TCP；同一应用另挂 ONC RPC UDP，不伪装 RSF UDP 能力。
        int udpPort;
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            udpPort = reservation.getLocalPort();
        }
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        incoming.connector("udp").bind("127.0.0.1", udpPort).protocol("oncrpc-udp");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf");
        outgoing.connector("tcp").protocol("rsf");
        outgoing.connector("udp").protocol("oncrpc-udp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后发布并订阅两个明确的协议入口。
            server.start();
            client.start();
            server.publisher().rsfService(SocketEchoService.class, value -> "tcp:" + value) //
                    .name("tcp").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "udp:" + value) //
                    .name("udp").protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<SocketEchoService> tcp = client.publisher().rsfService(SocketEchoService.class) //
                    .name("tcp").protocol("rsf").register();
            MetBindInfo<SocketEchoService> udp = client.publisher().rsfService(SocketEchoService.class) //
                    .name("udp").protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", new OncRpcServiceResource(SocketEchoService.class)).register();

            // 两种传输交错产生在途请求，必须保持各自的响应归属。
            InterAddress tcpAddress = server.getListenAddress("rsf");
            InterAddress udpAddress = server.getListenAddress("oncrpc-udp");
            assertEquals(udpPort, udpAddress.getPort());
            MetClient tcpClient = client.getRsfClient(tcpAddress);
            MetClient udpClient = client.getRsfClient(udpAddress);
            List<MetFuture> pending = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                Object[] parameters = { "call-" + index };
                // 2 * index 保存 TCP 结果，与后面的偶数下标断言对应。
                pending.add(tcpClient.asyncInvoke(tcp, "echo", new Class<?>[] { String.class }, parameters));
                // 2 * index + 1 保存 UDP 结果，与后面的奇数下标断言对应。
                pending.add(udpClient.asyncInvoke(udp, "echo", new Class<?>[] { String.class }, parameters));
            }
            for (int index = 0; index < 12; index++) {
                assertEquals("tcp:call-" + index, pending.get(index * 2).getData(5, TimeUnit.SECONDS));
                assertEquals("udp:call-" + index, pending.get(index * 2 + 1).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void closingThriftClientLeavesOtherProtocolClientsConnected() throws Exception {
        // 四协议共享服务端监听，Thrift 与其它协议分别由两个客户端实例持有。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf");
        ConnectorSettings listener = incoming.connector("tcp").bind("127.0.0.1", 0);
        listener.protocol("rsf");
        listener.protocol("dubbo");
        listener.protocol("thrift");
        listener.protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf");
        ConnectorSettings connector = outgoing.connector("tcp");
        connector.protocol("rsf");
        connector.protocol("dubbo");
        connector.protocol("oncrpc");
        MetConfiguration thriftConfiguration = new MetConfiguration().setDefaultProtocol("thrift");
        thriftConfiguration.connector("tcp").protocol("thrift");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext(); //
             MetContext exiting = thriftConfiguration.buildContext()) {
            // 所有实例启动后发布服务，再在所属客户端订阅对应协议。
            server.start();
            client.start();
            exiting.start();
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsf").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubbo").protocol("dubbo").register();
            server.publisher().rsfService(EchoService.Iface.class, value -> "thrift:" + value) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsf").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubbo").protocol("dubbo").register();
            MetBindInfo<EchoService.Iface> thrift = exiting.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift").bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> oncrpc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncrpc").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();

            // 建立所有协议连接，确认它们在独立客户端退出前已经可用。
            InterAddress rsfAddress = server.getListenAddress("rsf");
            InterAddress dubboAddress = server.getListenAddress("dubbo");
            InterAddress oncAddress = server.getListenAddress("oncrpc");
            SocketEchoService rsfRemote = client.getRsfClient(rsfAddress).getRemote(rsf);
            SocketEchoService dubboRemote = client.getRsfClient(dubboAddress).getRemote(dubbo);
            SocketEchoService oncRemote = client.getRsfClient(oncAddress).getRemote(oncrpc);
            assertEquals("rsf:before", rsfRemote.echo("before"));
            assertEquals("dubbo:before", dubboRemote.echo("before"));
            assertEquals("oncrpc:before", oncRemote.echo("before"));
            MetClient thriftRemote = exiting.getRsfClient(server.getListenAddress("thrift"));
            MetFuture reply = thriftRemote.asyncInvoke(thrift, "echo", //
                    new Class<?>[] { String.class }, new Object[] { "before" });
            assertEquals("thrift:before", reply.getData(5, TimeUnit.SECONDS));

            // 创建者只关闭自己持有的连接，服务端与另一个实例的三条通道继续可用。
            exiting.close();
            assertEquals("rsf:after", rsfRemote.echo("after"));
            assertEquals("dubbo:after", dubboRemote.echo("after"));
            assertEquals("oncrpc:after", oncRemote.echo("after"));
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void twoRuntimesConcurrentlyProvideAndConsumeAllFourTcpProtocols() throws Exception {
        // 两个运行实例各自以同一 TCP 端口挂载四协议，同时接受和发起请求。
        MetConfiguration leftConfiguration = new MetConfiguration().setDefaultProtocol("rsf");
        ConnectorSettings leftListener = leftConfiguration.connector("tcp").bind("127.0.0.1", 0);
        leftListener.protocol("rsf");
        leftListener.protocol("dubbo");
        leftListener.protocol("thrift");
        leftListener.protocol("oncrpc");
        MetConfiguration rightConfiguration = new MetConfiguration().setDefaultProtocol("rsf");
        ConnectorSettings rightListener = rightConfiguration.connector("tcp").bind("127.0.0.1", 0);
        rightListener.protocol("rsf");
        rightListener.protocol("dubbo");
        rightListener.protocol("thrift");
        rightListener.protocol("oncrpc");
        try (MetContext left = leftConfiguration.buildContext(); //
             MetContext right = rightConfiguration.buildContext()) {
            // 启动后在两端分别发布，并在对端订阅；服务名与返回标记明确区分方向。
            left.start();
            right.start();
            List<MetContext> runtimes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            // 方向 0：左端提供服务，右端消费，结果使用 left 标记。
            runtimes.add(left);
            names.add("left");
            // 方向 1：右端提供服务，左端消费，结果使用 right 标记。
            runtimes.add(right);
            names.add("right");
            runtimes = List.copyOf(runtimes);
            names = List.copyOf(names);
            List<String> protocols = new ArrayList<>();
            protocols.add("rsf"); // 当前方向下标 0，与 RSF 绑定和客户端对应。
            protocols.add("dubbo"); // 当前方向下标 1，与 Dubbo 绑定和客户端对应。
            protocols.add("thrift"); // 当前方向下标 2，与 Thrift 绑定和客户端对应。
            protocols.add("oncrpc"); // 当前方向下标 3，与 ONC RPC 绑定和客户端对应。
            protocols = List.copyOf(protocols);
            List<MetClient> callers = new ArrayList<>();
            List<MetBindInfo<?>> bindings = new ArrayList<>();
            List<String> markers = new ArrayList<>();
            for (int direction = 0; direction < runtimes.size(); direction++) {
                MetContext provider = runtimes.get(direction);
                MetContext consumer = runtimes.get(1 - direction);
                String origin = names.get(direction);
                provider.publisher().rsfService(SocketEchoService.class, value -> origin + ":rsf:" + value) //
                        .name(origin + "-rsf").protocol("rsf").register();
                provider.publisher().rsfService(SocketEchoService.class, value -> origin + ":dubbo:" + value) //
                        .name(origin + "-dubbo").protocol("dubbo").register();
                provider.publisher().rsfService(EchoService.Iface.class, value -> origin + ":thrift:" + value) //
                        .name(origin + "-thrift").protocol("thrift") //
                        .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
                provider.publisher().rsfService(SocketEchoService.class, value -> origin + ":oncrpc:" + value) //
                        .name(origin + "-oncrpc").protocol("oncrpc") //
                        .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
                MetBindInfo<SocketEchoService> rsf = consumer.publisher().rsfService(SocketEchoService.class) //
                        .name(origin + "-rsf").protocol("rsf").register();
                MetBindInfo<SocketEchoService> dubbo = consumer.publisher().rsfService(SocketEchoService.class) //
                        .name(origin + "-dubbo").protocol("dubbo").register();
                MetBindInfo<EchoService.Iface> thrift = consumer.publisher().rsfService(EchoService.Iface.class) //
                        .name(origin + "-thrift").protocol("thrift") //
                        .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
                MetBindInfo<SocketEchoService> oncrpc = consumer.publisher().rsfService(SocketEchoService.class) //
                        .name(origin + "-oncrpc").protocol("oncrpc") //
                        .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
                bindings.add(rsf); // 当前方向下标 0，对应 protocols[0] 创建的客户端。
                bindings.add(dubbo); // 当前方向下标 1，对应 protocols[1] 创建的客户端。
                bindings.add(thrift); // 当前方向下标 2，对应 protocols[2] 创建的客户端。
                bindings.add(oncrpc); // 当前方向下标 3，对应 protocols[3] 创建的客户端。
                int port = provider.getListenAddress("rsf").getPort();
                for (String protocol : protocols) {
                    InterAddress address = provider.getListenAddress(protocol);
                    assertEquals(port, address.getPort());
                    callers.add(consumer.getRsfClient(address));
                    markers.add(origin + ":" + protocol + ":");
                }
            }

            // 八个方向交错提交，所有调用均在读取结果前发出；ID 和路由结果不能串线。
            List<MetFuture> pending = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int sequence = 0; sequence < 12; sequence++) {
                for (int direction = 0; direction < callers.size(); direction++) {
                    String value = "request-" + sequence;
                    MetFuture reply = callers.get(direction).asyncInvoke(bindings.get(direction), "echo", //
                            new Class<?>[] { String.class }, new Object[] { value });
                    pending.add(reply);
                    expected.add(markers.get(direction) + value);
                }
            }
            for (int index = 0; index < pending.size(); index++) {
                assertEquals(expected.get(index), pending.get(index).getData(8, TimeUnit.SECONDS));
            }
        }
    }

}
