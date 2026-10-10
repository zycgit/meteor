/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
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
import net.hasor.meteor.address.route.ArgsKey;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
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
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.publisher().rsfService(Echo.class).toInstance(new EchoService() {
                @Override
                public String slow(int millis) throws InterruptedException {
                    entered.countDown();
                    assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                    return "done";
                }
            }).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            server.start();
            client.start();

            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            try {
                MetFuture call = remote.asyncInvoke(service, "slow", new Class<?>[] { int.class }, new Object[] { 1 });
                assertTrue(entered.await(5, TimeUnit.SECONDS));

                client.close();
                assertTrue("Close must finish pending calls before returning", call.isDone());
                Throwable failure = assertFailure(call);
                boolean closedFailure = failure instanceof IOException || failure instanceof IllegalStateException;
                assertTrue("Unexpected close failure: " + failure, closedFailure);
                Class<?>[] types = { String.class };
                MetFuture afterClose = remote.asyncInvoke(service, "echo", types, new Object[] { "closed" });
                assertFailure(afterClose);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void failedBindRollsBackNetworkResources() throws Exception {
        // 占用监听地址后启动，失败必须回收网络资源。
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try (MetContext runtime = this.configuration(occupied.getLocalPort()).buildContext()) {
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
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertFalse(runtime.isOnline());
            assertTrue(runtime.runProtocols().isEmpty());
        }
    }

    @Test
    public void manualOnlineStateControlsRemoteAdmissionAndStartIsIdempotent() throws Exception {
        // 禁用自动上线，显式上线后才接受远程调用。
        MetConfiguration config = this.configuration();
        config.setAutomaticOnline(false);
        try (MetContext server = config.buildContext(); //
             MetContext client = this.configuration().buildContext()) {
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            server.start();
            client.start();

            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));
            Echo remote = caller.getRemote(service);
            assertFalse(server.isOnline());
            Class<?>[] types = { String.class };
            MetFuture denied = caller.asyncInvoke(service, "echo", types, new Object[] { "offline" });
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> denied.getData(5, TimeUnit.SECONDS));
            Throwable failure = rejected.getCause();
            assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());

            // 重复上线不改变语义，显式下线也不会被再次 start 覆盖。
            server.online();
            server.online();
            assertEquals("online", remote.echo("online"));
            server.offline();
            server.start();
            assertFalse("Repeated start must not undo explicit offline", server.isOnline());

            // 关闭幂等，监听释放后不能重新启动。
            server.close();
            server.close();
            assertNull(server.bindAddress("rsf"));
            assertTrue(server.runProtocols().isEmpty());
            assertThrows(IllegalStateException.class, server::start);
            assertThrows(IllegalStateException.class, server::online);
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
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> config.buildContext());
        assertTrue(failure.getMessage().contains("missing-factory"));
        try (ServerSocket rebound = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(firstPort, rebound.getLocalPort());
        }
    }

    @Test
    public void closeBeforeStartAndConstructorFailureReleaseResources() throws Exception {
        // 构建后未启动也可以关闭，关闭后不可重新启动。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {

            context.close();
            context.close();
            assertThrows(IllegalStateException.class, context::start);
            MetConfiguration config = this.configuration();
            config.setQueueMaxSize(0);
            assertThrows(IllegalArgumentException.class, () -> config.buildContext());
        }
    }

    @Test
    public void invalidArgsKeyFailsDuringConstruction() throws Exception {
        // 路由参数键必须是可构建的具体实现。
        MetConfiguration invalidRoute = this.configuration();
        invalidRoute.setArgsKey(ArgsKey.class);
        assertThrows(IllegalArgumentException.class, () -> invalidRoute.buildContext());
    }
}
