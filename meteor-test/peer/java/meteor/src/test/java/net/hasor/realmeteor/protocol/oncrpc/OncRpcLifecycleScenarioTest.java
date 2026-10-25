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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** ONC RPC 的 Future、并发、取消、期限及关闭均从公开调用入口验证。 */
public class OncRpcLifecycleScenarioTest {
    @Test(timeout = 15000)
    public void tcpPendingCallWaitsForBusinessCompletion() throws Exception {
        // 用业务闩锁明确控制返回时刻，观察等待超时和 RPC 完成的区别。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc"));

            // getData 的短等待不会取消或完成仍在业务中执行的请求。
            MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                    new Object[] { "blocked" });
            try {
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
            } finally {
                blocked.release.countDown();
            }

            // 只有业务解除阻塞并返回，Future 才携带真实结果完成。
            assertEquals("blocked", pending.getData(3, TimeUnit.SECONDS));
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void tcpConcurrentCallsKeepTheirOwnXidResults() throws Exception {
        // 多个调用共享同一客户端连接，同时提交后再读取响应。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).timeout(5000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc"));

            // 整组请求先进入调用链，不能以串行调用冒充并发场景。
            List<MetFuture> pending = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "call-" + index }));
            }

            // 响应到达顺序不能改变 XID 与原 Future 的对应关系。
            for (int index = 0; index < pending.size(); index++) {
                assertEquals("call-" + index, pending.get(index).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void tcpCancelledReplyCannotCompleteFollowingCall() throws Exception {
        // 两条业务各有独立闩锁，让旧响应与仍在途的新请求精确相遇。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc"));
            try {
                // 确认目标请求已进入远端业务，再取消并启动另一条在途调用。
                Class<?>[] types = { String.class };
                MetFuture cancelled = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 被取消请求的迟到响应不能完成仍受闩锁控制的另一条请求。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                assertTrue(cancelled.isCancelled());
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
    public void tcpTimedOutReplyCannotCompleteFollowingCall() throws Exception {
        // RPC 期限先于原业务完成，后续请求使用同一真实客户端连接。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).timeout(700).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc"));
            try {
                // Future 必须以 RPC 超时结束，不把测试等待期限当成 RPC 期限。
                Class<?>[] types = { String.class };
                MetFuture expired = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    expired.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 原业务现在返回，后续请求仍然只能等待自己的业务结果。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                following.release.countDown();
                assertEquals("following", pending.getData(3, TimeUnit.SECONDS));
            } finally {
                abandoned.release.countDown();
                following.release.countDown();
            }
        }
    }

    @Test(timeout = 20000)
    public void tcpClientCloseTerminatesPendingCallBeforeDeadline() throws Exception {
        // 请求期限长于关闭观察窗口，业务一直等待显式释放。
        MetConfiguration incoming = this.incoming("tcp");
        MetConfiguration outgoing = this.outgoing("tcp");
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                // Context 的所有者关闭客户端，不能由协议会话反向关闭上下文。
                MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "blocked" });
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                Future<?> closing = executor.submit(() -> {
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
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            }
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void udpPendingCallWaitsForBusinessCompletion() throws Exception {
        // 用业务闩锁明确控制返回时刻，观察等待超时和 RPC 完成的区别。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc-udp"));

            // getData 的短等待不会取消或完成仍在业务中执行的请求。
            MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                    new Object[] { "blocked" });
            try {
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
            } finally {
                blocked.release.countDown();
            }

            // 只有业务解除阻塞并返回，Future 才携带真实结果完成。
            assertEquals("blocked", pending.getData(3, TimeUnit.SECONDS));
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void udpConcurrentCallsKeepTheirOwnXidResults() throws Exception {
        // 多个调用共享同一客户端连接，同时提交后再读取响应。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(5000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc-udp"));

            // 整组请求先进入调用链，不能以串行调用冒充并发场景。
            List<MetFuture> pending = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "call-" + index }));
            }

            // 响应到达顺序不能改变 XID 与原 Future 的对应关系。
            for (int index = 0; index < pending.size(); index++) {
                assertEquals("call-" + index, pending.get(index).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void udpCancelledReplyCannotCompleteFollowingCall() throws Exception {
        // 两条业务各有独立闩锁，让旧响应与仍在途的新请求精确相遇。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc-udp"));
            try {
                // 确认目标请求已进入远端业务，再取消并启动另一条在途调用。
                Class<?>[] types = { String.class };
                MetFuture cancelled = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 被取消请求的迟到响应不能完成仍受闩锁控制的另一条请求。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                assertTrue(cancelled.isCancelled());
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
    public void udpTimedOutReplyCannotCompleteFollowingCall() throws Exception {
        // RPC 期限先于原业务完成，后续请求使用同一真实客户端连接。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        EchoGate abandoned = new EchoGate("abandoned");
        EchoGate following = new EchoGate("following");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(abandoned, following)) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(700).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc-udp"));
            try {
                // Future 必须以 RPC 超时结束，不把测试等待期限当成 RPC 期限。
                Class<?>[] types = { String.class };
                MetFuture expired = remote.asyncInvoke(service, "echo", types, new Object[] { "abandoned" });
                assertTrue(abandoned.entered.await(3, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    expired.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "following" });
                assertTrue(following.entered.await(3, TimeUnit.SECONDS));

                // 原业务现在返回，后续请求仍然只能等待自己的业务结果。
                abandoned.release.countDown();
                assertTrue(abandoned.exited.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.isDone());
                following.release.countDown();
                assertEquals("following", pending.getData(3, TimeUnit.SECONDS));
            } finally {
                abandoned.release.countDown();
                following.release.countDown();
            }
        }
    }

    @Test(timeout = 20000)
    public void udpClientCloseTerminatesPendingCallBeforeDeadline() throws Exception {
        // 请求期限长于关闭观察窗口，业务一直等待显式释放。
        MetConfiguration incoming = this.incoming("udp");
        MetConfiguration outgoing = this.outgoing("udp");
        EchoGate blocked = new EchoGate("blocked");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new BlockingEchoService(blocked)) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).timeout(10000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("oncrpc-udp"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                // Context 的所有者关闭客户端，不能由协议会话反向关闭上下文。
                MetFuture pending = remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "blocked" });
                assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
                Future<?> closing = executor.submit(() -> {
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
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            }
            assertTrue(blocked.exited.await(3, TimeUnit.SECONDS));
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
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(4);
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

    private static final class EchoGate {
        private final String         value;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited  = new CountDownLatch(1);

        private EchoGate(String value) {
            this.value = value;
        }
    }

    private static final class BlockingEchoService extends OncRpcDemoServiceImpl {
        private final EchoGate[] gates;

        private BlockingEchoService(EchoGate... gates) {
            this.gates = gates;
        }

        @Override
        public String echo(String value) {
            // 仅阻塞指定业务值，其它调用仍然经过同一条正常分派链。
            for (EchoGate gate : this.gates) {
                if (!StringUtils.equals(gate.value, value)) {
                    continue;
                }
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Business gate was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    gate.exited.countDown();
                }
                return value;
            }
            return value;
        }
    }
}
