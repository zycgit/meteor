/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.hprose.scenario.example.Echo;
import net.hasor.meteor.protocol.hprose.scenario.example.EchoService;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextLifecycleTest extends BootstrapTestSupport {

    @Test
    public void closeDrainsConnectionsAndCompletesOutstandingCalls() throws Exception {
        // 建立真实调用并阻塞服务，关闭调用方必须结束在途 Future。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 两端启动后，发布一个等待释放信号的服务。
            server.start();
            client.start();

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.publisher() //
                    .rsfService(Echo.class) //
                    .toInstance(new EchoService() {
                        @Override
                        public String slow(int millis) throws InterruptedException {
                            entered.countDown();
                            assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                            return "done";
                        }
                    }) //
                    .register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            // 等待远程方法开始执行，保证关闭时确实存在未完成的调用。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            try {
                Class<?>[] parameterTypes = { int.class };
                Object[] arguments = { 1 };
                MetFuture call = remote.asyncInvoke(service, "slow", parameterTypes, arguments);
                assertTrue(entered.await(5, TimeUnit.SECONDS));

                // 关闭调用方后，等待中的 Future 应立即结束并报告关闭原因。
                client.close();
                assertTrue("Close must finish pending calls before returning", call.isDone());
                Throwable failure = assertFailure(call);
                boolean closedFailure = failure instanceof IOException || failure instanceof IllegalStateException;
                assertTrue("Unexpected close failure: " + failure, closedFailure);
            } finally {
                // 解除服务端阻塞，让剩余资源按正常生命周期退出。
                release.countDown();
            }
        }
    }

    @Test
    public void failedBindRollsBackNetworkResources() throws Exception {
        // 占用监听地址后启动，失败必须回收网络资源。
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            MetConfiguration configuration = this.configuration(occupied.getLocalPort());
            try (MetContext runtime = configuration.buildContext()) {
                // 监听绑定失败后，实例不能上线或留下运行中的协议。
                assertThrows(IllegalStateException.class, runtime::start);
                assertFalse(runtime.isOnline());
                assertTrue(runtime.runProtocols().isEmpty());
            }
        }
    }

    @Test
    public void invalidAddressMaintenanceConfigurationRollsBackNetworkStartup() throws Exception {
        // 地址维护配置错误时，已经启动的网络资源必须回收。
        MetConfiguration config = this.configuration();
        config.setRefreshCacheTime(0);
        try (MetContext runtime = config.buildContext()) {
            // 启动失败保留配置错误原因，同时回滚已创建的网络资源。
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertFalse(runtime.isOnline());
            assertTrue(runtime.runProtocols().isEmpty());
        }
    }

    @Test
    public void manualOnlineStateControlsRemoteAdmission() throws Exception {
        // 禁用自动上线，显式上线后才接受远程调用。
        MetConfiguration config = this.configuration();
        config.setAutomaticOnline(false);
        try (MetContext server = config.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            // 启动并发布服务后，服务端仍维持手动上线前的离线状态。
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));
            Echo remote = caller.getRemote(service);

            // 离线时通过真实网络调用，验证返回的是准入拒绝。
            assertFalse(server.isOnline());
            Class<?>[] types = { String.class };
            MetFuture denied = caller.asyncInvoke(service, "echo", types, new Object[] { "offline" });
            var rejected = assertThrows(ExecutionException.class, () -> denied.getData(5, TimeUnit.SECONDS));
            Throwable failure = rejected.getCause();
            assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());

            // 显式上线后，同一个客户端可以正常调用。
            server.online();
            assertEquals("online", remote.echo("online"));
        }
    }

    @Test
    public void repeatedStartDoesNotUndoExplicitOffline() throws Exception {
        // 创建已经启动且具备远程服务的应用。
        MetConfiguration incoming = this.configuration();
        MetConfiguration outgoing = this.configuration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));

            // 已运行的应用主动下线后，重复启动不能恢复远程接入。
            server.offline();
            server.start();
            assertFalse(server.isOnline());
            Class<?>[] types = { String.class };
            MetFuture denied = caller.asyncInvoke(service, "echo", types, new Object[] { "offline" });
            var rejected = assertThrows(ExecutionException.class, () -> denied.getData(5, TimeUnit.SECONDS));
            assertEquals(ProtocolStatus.Forbidden, ((MetException) rejected.getCause()).getStatus());
        }
    }

    @Test
    public void repeatedOnlineKeepsRemoteAdmission() throws Exception {
        // 禁用自动上线，准备只能由显式上线操作开放的服务。
        MetConfiguration incoming = this.configuration().setAutomaticOnline(false);
        MetConfiguration outgoing = this.configuration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 连续上线不改变已开放的远程调用能力。
            server.online();
            server.online();
            assertEquals("online", remote.echo("online"));
        }
    }

    @Test
    public void repeatedCloseKeepsListenersReleased() throws Exception {
        // 先启动真实监听，再记录需要验证已释放的端口。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.bindAddress("rsf").getPort();

            // 重复关闭应保持幂等，不恢复任何监听资源。
            context.close();
            context.close();

            // 关闭可重复执行，真实监听端口已经交回操作系统。
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
            try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test
    public void closedContextCannotRestart() throws Exception {
        // 正常启动后关闭实例，进入不可重新启动的终止状态。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.close();

            // 再次启动必须报告生命周期错误。
            assertThrows(IllegalStateException.class, context::start);
        }
    }

    @Test
    public void closedContextCannotGoOnline() throws Exception {
        // 正常启动后关闭实例，释放监听和调用资源。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.close();

            // 上线操作不能复活已关闭的实例。
            assertThrows(IllegalStateException.class, context::online);
        }
    }

    @Test
    public void closedClientRejectsANewRemoteInvocation() throws Exception {
        // 先完成两端启动及服务发布，保留关闭前取得的调用入口。
        MetConfiguration incoming = this.configuration();
        MetConfiguration outgoing = this.configuration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));

            // 客户端关闭后，既有入口发起的新调用也必须失败。
            client.close();
            Class<?>[] types = { String.class };
            MetFuture rejected = caller.asyncInvoke(service, "echo", types, new Object[] { "closed" });
            assertFailure(rejected);
        }
    }

    @Test
    public void invalidSpiIsRejectedBeforeAnyListenerIsOpened() throws Exception {
        // 缺失 SPI 在构建阶段报错，不得占用监听端口。
        MetConfiguration config = this.configuration(this.port());
        int firstPort = config.getBindAddressSet("rsf").getPort();
        config.connector("missing-factory").protocol("hprose");
        config.setLocalDiskCache(true);
        config.setDataHome(this.temporary.newFolder().toPath());

        // SPI 校验在监听之前发生，错误信息需包含缺失的工厂名称。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> config.buildContext());
        assertTrue(failure.getMessage().contains("missing-factory"));

        // 用操作系统重新绑定端口，确认构建失败没有提前占用它。
        try (ServerSocket rebound = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(firstPort, rebound.getLocalPort());
        }
    }

    @Test
    public void closeBeforeStartReleasesResources() throws Exception {
        // 构建后未启动也可以关闭，关闭后不可重新启动。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            // 未启动时重复关闭仍然有效，此后启动应被拒绝。
            context.close();
            context.close();
            assertThrows(IllegalStateException.class, context::start);
        }
    }

    @Test
    public void invalidExecutorConfigurationReleasesConstructionResources() throws Exception {
        // 调度配置非法时构建失败，由测试收尾检查确认没有遗留框架线程。
        MetConfiguration invalid = this.configuration();
        invalid.setQueueMaxSize(0);

        // 构建直接失败，线程回收由公共收尾检查验证。
        assertThrows(IllegalArgumentException.class, invalid::buildContext);
    }
}
