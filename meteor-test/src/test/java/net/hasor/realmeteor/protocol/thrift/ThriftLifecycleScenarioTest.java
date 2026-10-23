/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实 Thrift 请求的完成、终止及迟到响应隔离。 */
public class ThriftLifecycleScenarioTest {
    @Test(timeout = 15000)
    public void framedTcpCallRemainsPendingUntilBusinessCompletes() throws Exception {
        // Framed TCP 业务由闩锁控制完成时刻，客户端期限留足业务执行时间。
        MetConfiguration incoming = this.tcpServerConfiguration(true);
        MetConfiguration outgoing = this.tcpClientConfiguration(true);
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动后通过公开 API 发布普通业务实现，并订阅同一份 IDL 元数据。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift"));

            // 到达远端业务不等于完成 RPC，等待上限不会自行结束该请求。
            MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                    new Object[] { "blocked" });
            try {
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
            } finally {
                blocked.release.countDown();
            }

            // 释放业务后，调用方才获得真正的响应数据。
            assertEquals("blocked", pending.getData(3, TimeUnit.SECONDS));
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void concurrentRawTcpCallsKeepTheirOwnResults() throws Exception {
        // Raw TCP 不依赖长度帧，多个调用共享真实连接并各自携带 Thrift sequence ID。
        MetConfiguration incoming = this.tcpServerConfiguration(false);
        MetConfiguration outgoing = this.tcpClientConfiguration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(5000).register();

            // 一次提交整组调用，再逐个等待，避免将并发场景写成串行调用。
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift"));
            List<MetFuture> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "raw-" + i }));
            }

            // 每个 Future 只能收到自己的请求值，不能按响应到达顺序错配。
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("raw-" + i, pending.get(i).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void concurrentHttp2CallsKeepTheirOwnResults() throws Exception {
        // HTTP/2 在一个监听上承载多个 Thrift 请求，每条 stream 保持独立的交换状态。
        MetConfiguration incoming = this.httpServerConfiguration();
        MetConfiguration outgoing = this.httpClientConfiguration(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(5000).register();

            // 所有请求先进入调用链，再读取各自结果。
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift-http"));
            List<MetFuture> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "http2-" + i }));
            }

            // HTTP stream 和 Thrift 消息身份共同保持结果归属。
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("http2-" + i, pending.get(i).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void cancelledFramedTcpReplyDoesNotCompleteAnotherPendingCall() throws Exception {
        // 同一 TCP 连接上保留两条独立业务，第二条在第一条取消后才进入。
        MetConfiguration incoming = this.tcpServerConfiguration(true);
        MetConfiguration outgoing = this.tcpClientConfiguration(true);
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift"));

            // 确认目标请求已在远端执行后取消 Future，再启动仍需等待业务的新请求。
            try {
                Class<?>[] types = { String.class };
                MetFuture cancelled = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                assertTrue(cancelled.isCancelled());
                assertTrue(cancelled.isDone());
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 先释放已取消业务，使迟到响应与另一条在途请求相遇。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                assertTrue(cancelled.isCancelled());

                // 第二条业务只有被单独释放，才返回属于自己的结果。
                following.release.countDown();
                assertEquals("following", pending.getData(3, TimeUnit.SECONDS));
                assertTrue(following.exited.await(3, TimeUnit.SECONDS));
            } finally {
                abandoned.release.countDown();
                following.release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void timedOutRawTcpReplyDoesNotCompleteAnotherPendingCall() throws Exception {
        // 原始 TCP 请求由独立闩锁保持在途，调用方设置真实 RPC 期限。
        MetConfiguration incoming = this.tcpServerConfiguration(false);
        MetConfiguration outgoing = this.tcpClientConfiguration(false);
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(3000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift"));

            // 有界 get 只观察 RPC 自己的超时结果，不把测试等待超时当成请求超时。
            try {
                Class<?>[] types = { String.class };
                MetFuture expired = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    expired.getData(5, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 过期业务先返回，另一条请求仍必须等待自己的业务被释放。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                following.release.countDown();
                assertEquals("following", pending.getData(3, TimeUnit.SECONDS));
                assertTrue(following.exited.await(3, TimeUnit.SECONDS));
            } finally {
                abandoned.release.countDown();
                following.release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void timedOutHttp1CallDoesNotPoisonFollowingCalls() throws Exception {
        // HTTP/1 的在途交换保持阻塞，使客户端期限先于业务返回。
        MetConfiguration incoming = this.httpServerConfiguration();
        MetConfiguration outgoing = this.httpClientConfiguration(1);
        EchoGate abandoned = new EchoGate("abandoned");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(abandoned)) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(600).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift-http"));

            // 请求已经进入业务后观察调用方的 RPC 超时。
            try {
                Class<?>[] types = { String.class };
                MetFuture expired = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    expired.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                abandoned.release.countDown();
            }

            // 等旧业务退出后立即提交新请求，取消的 HTTP 交换不能污染后续结果。
            assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
            MetFuture following = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                    new Object[] { "after timeout" });
            assertEquals("after timeout", following.getData(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void cancellingOneHttp2CallDoesNotCancelOtherCalls() throws Exception {
        // 一条 HTTP/2 stream 等待业务，另一条 stream 保持正常响应能力。
        MetConfiguration incoming = this.httpServerConfiguration();
        MetConfiguration outgoing = this.httpClientConfiguration(2);
        EchoGate abandoned = new EchoGate("abandoned");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(abandoned)) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift-http"));

            // 取消已进入业务的请求时，同一客户端仍能完成其他 Thrift 调用。
            try {
                Class<?>[] types = { String.class };
                MetFuture cancelled = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                assertTrue(cancelled.isCancelled());
                MetFuture surviving = remote.asyncInvoke(service, "echo", types, new Object[] { "surviving" });
                assertEquals("surviving", surviving.getData(3, TimeUnit.SECONDS));

                // 已取消业务退出后，迟到结果也不能影响下一条 stream 的结果。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                MetFuture following = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertEquals("following", following.getData(3, TimeUnit.SECONDS));
                assertTrue(cancelled.isCancelled());
            } finally {
                abandoned.release.countDown();
            }
        }
    }

    @Test(timeout = 20000)
    public void closingHttp2ClientFailsPendingCallBeforeItsDeadline() throws Exception {
        // 请求期限长于关闭观察窗口，避免将期限到达误认为关闭通知。
        MetConfiguration incoming = this.httpServerConfiguration();
        MetConfiguration outgoing = this.httpClientConfiguration(2);
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift-http"));

            // 关闭持有在途请求的客户端，Future 应通过失败结束。
            ExecutorService closingExecutor = Executors.newSingleThreadExecutor();
            try {
                MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "blocked" });
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                Future<?> closing = closingExecutor.submit(() -> {
                    client.close();
                    return null;
                });
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
                closing.get(3, TimeUnit.SECONDS);
            } finally {
                blocked.release.countDown();
                closingExecutor.shutdownNow();
                assertTrue(closingExecutor.awaitTermination(3, TimeUnit.SECONDS));
            }

            // 即使传输取消中断业务，也必须能观察到业务退出并释放测试资源。
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 20000)
    public void closingFramedTcpServerFailsPendingCallBeforeItsDeadline() throws Exception {
        // 服务端关闭发生在真实请求进入业务之后，客户端期限仍远未耗尽。
        MetConfiguration incoming = this.tcpServerConfiguration(true);
        MetConfiguration outgoing = this.tcpClientConfiguration(true);
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("thrift"));

            // 通道终止必须尽快传到调用方，不能一直等待 RPC 期限。
            ExecutorService closingExecutor = Executors.newSingleThreadExecutor();
            try {
                MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "blocked" });
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                Future<?> closing = closingExecutor.submit(() -> {
                    server.close();
                    return null;
                });
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
                closing.get(3, TimeUnit.SECONDS);
            } finally {
                blocked.release.countDown();
                closingExecutor.shutdownNow();
                assertTrue(closingExecutor.awaitTermination(3, TimeUnit.SECONDS));
            }

            // 关闭过程允许中断业务，但不能遗留仍占用执行线程的调用。
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    private MetConfiguration tcpServerConfiguration(boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(4);
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("thrift").option("encoding", "binary").option("framed", framed);
        return configuration;
    }

    private MetConfiguration tcpClientConfiguration(boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").protocol("thrift") //
                .option("encoding", "binary").option("framed", framed);
        return configuration;
    }

    private MetConfiguration httpServerConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(4);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("thrift-http").contextPath("/thrift").option("encoding", "binary");
        return configuration;
    }

    private MetConfiguration httpClientConfiguration(int version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").option("httpVersion", version) //
                .protocol("thrift-http").contextPath("/thrift").option("encoding", "binary");
        return configuration;
    }

    private static class BlockingEchoService extends ThriftDemoServiceImpl {
        private final List<EchoGate> gates;

        private BlockingEchoService(EchoGate... gates) {
            this.gates = Arrays.asList(gates);
        }

        @Override
        public String echo(String value) {
            // 只阻塞场景指定的请求，其他业务值仍按正常接口立即返回。
            for (EchoGate gate : this.gates) {
                if (StringUtils.equals(gate.value, value)) {
                    gate.awaitRelease();
                    break;
                }
            }
            return super.echo(value);
        }
    }

    private static class EchoGate {
        private final String         value;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited  = new CountDownLatch(1);

        private EchoGate(String value) {
            this.value = value;
        }

        private void awaitRelease() {
            // 入口和出口均有明确信号，避免用固定休眠推断远程业务的执行阶段。
            this.entered.countDown();
            try {
                if (!this.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out: " + this.value);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test business interrupted: " + this.value, failure);
            } finally {
                this.exited.countDown();
            }
        }
    }
}
