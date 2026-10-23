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
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 公开 bootstrap 链路上的 UDP 丢包、重传和重复应答缓存。 */
public class OncRpcUdpReliabilityScenarioTest {
    @Test(timeout = 15000)
    public void retransmitsAfterFirstRequestIsLost() throws Exception {
        // 代理丢掉第一份请求，服务端只会看到客户端重新发送的原始报文。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 30000);
        MetConfiguration outgoing = this.outgoing(4, 5000);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        OncRpcDatagramRelay.Fault fault = OncRpcDatagramRelay.Fault.DROP_FIRST_REQUEST;
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, fault); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动容器后发布、订阅，业务代理始终经过真实 UDP 通道。
            server.start();
            client.start();
            OncRpcDemoServiceImpl implementation = new OncRpcDemoServiceImpl();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            InterAddress address = this.relayAddress(client, relay);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 有限重传恢复丢包，业务只执行一次。
            assertEquals(1, remote.increment(1));
            assertTrue(relay.clientRequests() >= 2);
            assertEquals(1, implementation.count());
        }
    }

    @Test(timeout = 15000)
    public void retransmittedRequestReplaysLostReplyWithoutRepeatingBusiness() throws Exception {
        // 首个应答丢失后，客户端的重传必须命中服务端已完成的应答缓存。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 30000);
        MetConfiguration outgoing = this.outgoing(4, 5000);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, OncRpcDatagramRelay.Fault.DROP_FIRST_REPLY); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端完成启动后才装配业务服务。
            server.start();
            client.start();
            OncRpcDemoServiceImpl implementation = new OncRpcDemoServiceImpl();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            OncRpcDemoService remote = client.getRsfClient(this.relayAddress(client, relay)).getRemote(service);

            // 收到的缓存应答与首次发送的应答完全一致，计数器没有再次增加。
            assertEquals(1, remote.increment(1));
            assertTrue(relay.awaitServerReplies(2, 3, TimeUnit.SECONDS));
            assertArrayEquals(relay.firstReply(), relay.lastReply());
            assertEquals(1, implementation.count());
        }
    }

    @Test(timeout = 15000)
    public void duplicateWhileBusinessIsPendingDoesNotDispatchAgain() throws Exception {
        // 暂停首次业务调用，用同一来源地址重放仍在执行中的原始请求。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 30000);
        MetConfiguration outgoing = this.outgoing(0, 5000);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        BlockingIncrementService implementation = new BlockingIncrementService();
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, OncRpcDatagramRelay.Fault.NONE); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 闸门明确区分业务执行阶段，禁用自动重传让重复报文只来自本次注入。
            try {
                InterAddress address = this.relayAddress(client, relay);
                MetFuture pending = client.getRsfClient(address) //
                        .asyncInvoke(service, "increment", new Class<?>[] { int.class }, new Object[] { 1 });
                assertTrue(implementation.entered.await(3, TimeUnit.SECONDS));
                relay.replayFirstRequest();
                boolean dispatchedTwice = implementation.duplicateEntered.await(200, TimeUnit.MILLISECONDS);
                assertFalse("Pending duplicate must not invoke the business method again", dispatchedTwice);

                // 原调用结束后只生成一个业务结果。
                implementation.release.countDown();
                assertEquals(Integer.valueOf(1), pending.getData(3, TimeUnit.SECONDS));
                assertEquals(1, implementation.dispatches.get());
            } finally {
                implementation.release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void duplicateAfterCompletionReplaysIdenticalReply() throws Exception {
        // 先完成调用，再显式重放已完成的同一份原始请求。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 30000);
        MetConfiguration outgoing = this.outgoing(0, 5000);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, OncRpcDatagramRelay.Fault.NONE); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布和订阅使用同一份过程契约，代理只改变报文到达次数。
            server.start();
            client.start();
            OncRpcDemoServiceImpl implementation = new OncRpcDemoServiceImpl();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            OncRpcDemoService remote = client.getRsfClient(this.relayAddress(client, relay)).getRemote(service);
            assertEquals(1, remote.increment(1));

            // 重复调用返回相同线报文，不重新执行已完成的业务。
            relay.replayFirstRequest();
            assertTrue(relay.awaitServerReplies(2, 3, TimeUnit.SECONDS));
            assertArrayEquals(relay.firstReply(), relay.lastReply());
            assertEquals(1, implementation.count());
        }
    }

    @Test(timeout = 15000)
    public void expiredDuplicateReplyAllowsSameDatagramToExecuteAgain() throws Exception {
        // 缓存设置为短期有效，以同一份请求观察过期前后的业务执行边界。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 60);
        MetConfiguration outgoing = this.outgoing(0, 5000);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, OncRpcDatagramRelay.Fault.NONE); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            OncRpcDemoServiceImpl implementation = new OncRpcDemoServiceImpl();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            OncRpcDemoService remote = client.getRsfClient(this.relayAddress(client, relay)).getRemote(service);
            assertEquals(1, remote.increment(1));

            // 这里的等待用于使真实时间缓存过期，不用于替代异步完成信号。
            Thread.sleep(150);
            relay.replayFirstRequest();
            assertTrue(relay.awaitServerReplies(2, 3, TimeUnit.SECONDS));
            assertEquals(2, remote.count());
        }
    }

    @Test(timeout = 15000)
    public void disablingRetriesLeavesLostRequestToCoreDeadline() throws Exception {
        // 禁用协议重传并丢掉所有请求，调用期限仍由公开 RPC 超时配置控制。
        int port = this.freePort();
        MetConfiguration incoming = this.incoming(port, 30000);
        MetConfiguration outgoing = this.outgoing(0, 250);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        OncRpcDatagramRelay.Fault fault = OncRpcDatagramRelay.Fault.DROP_ALL_REQUESTS;
        try (OncRpcDatagramRelay relay = new OncRpcDatagramRelay(target, fault); //
             MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            OncRpcDemoServiceImpl implementation = new OncRpcDemoServiceImpl();
            server.publisher().rsfService(OncRpcDemoService.class, implementation) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(250).register();

            // 请求只发送一份，业务未执行，调用方得到内核超时而非协议伪造的应答。
            InterAddress address = this.relayAddress(client, relay);
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "increment", new Class<?>[] { int.class }, new Object[] { 1 });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof MetTimeoutException);
            assertEquals(1, relay.clientRequests());
            assertEquals(0, implementation.count());
        }
    }

    private int freePort() throws Exception {
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return reservation.getLocalPort();
        }
    }

    private MetConfiguration incoming(int port, int cacheTtl) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").bind("127.0.0.1", port).protocol("oncrpc-udp") //
                .option("duplicateCacheSize", 1024).option("duplicateCacheTtl", cacheTtl);
        return configuration;
    }

    private MetConfiguration outgoing(int attempts, int timeout) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setRequestTimeout(timeout);
        configuration.connector("udp").protocol("oncrpc-udp") //
                .option("retryInitialDelay", 20) //
                .option("retryMaxDelay", 80).option("retryAttempts", attempts);
        return configuration;
    }

    private InterAddress relayAddress(MetContext client, OncRpcDatagramRelay relay) {
        String unit = client.getSettings().getUnitName();
        return new InterAddress("oncrpc-udp", "127.0.0.1", relay.port(), unit);
    }

    public static class BlockingIncrementService extends OncRpcDemoServiceImpl {
        private final CountDownLatch entered          = new CountDownLatch(1);
        private final CountDownLatch duplicateEntered = new CountDownLatch(1);
        private final CountDownLatch release          = new CountDownLatch(1);
        private final AtomicInteger  dispatches       = new AtomicInteger();

        @Override
        public int increment(int delta) {
            // 保留首次调用的执行窗口；若错误分派重复请求，独立信号会揭示该错误。
            if (this.dispatches.incrementAndGet() > 1) {
                this.duplicateEntered.countDown();
            }
            this.entered.countDown();
            try {
                if (!this.release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Business gate did not open");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Business gate interrupted", interrupted);
            }
            return super.increment(delta);
        }
    }
}
