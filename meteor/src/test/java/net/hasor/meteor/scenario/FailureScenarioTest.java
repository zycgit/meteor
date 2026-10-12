/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 失败必须能被调用方观察，且不能妨碍后续请求。 */
public class FailureScenarioTest {
    @Test
    public void businessFailureAndOfflineRejectionAreDifferentFromTimeout() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Work.class, new Worker()).register();
            MetBindInfo<Work> service = client.publisher().rsfService(Work.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));

            // 业务失败保留异常响应，不应退化成请求超时。
            MetFuture failed = remote.asyncInvoke(service, "fail", new Class<?>[0], new Object[0]);
            Throwable business = failure(failed);
            assertTrue(business.toString(), business instanceof MetException);
            assertFalse(business instanceof MetTimeoutException);
            assertTrue(business.getMessage().contains("inventory unavailable"));

            // 离线后监听仍在，但业务请求被明确拒绝；上线后原客户端可继续调用。
            server.offline();
            Throwable offline = failure(remote.asyncInvoke(service, "quick", new Class<?>[0], new Object[0]));
            assertEquals(ProtocolStatus.Forbidden, ((MetException) offline).getStatus());
            server.online();
            assertEquals("ready", remote.getRemote(service).quick());
        }
    }

    @Test
    public void timeoutReleasesTheRequestWhileTheBusinessTaskIsStillRunning() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.setQueueMinPoolSize(2);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        Worker worker = new Worker();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Work.class, worker).register();
            MetBindInfo<Work> service = client.publisher().rsfService(Work.class).timeout(700).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).quick());

            // 使用业务信号固定执行阶段，等待 RPC 的截止时间，而不是休眠模拟超时。
            MetFuture waiting = remote.asyncInvoke(service, "waitForRelease", new Class<?>[0], new Object[0]);
            assertTrue(worker.entered.await(3, TimeUnit.SECONDS));
            assertTrue(failure(waiting) instanceof MetTimeoutException);
            assertEquals(1, worker.release.getCount());
            assertEquals("ready", remote.getRemote(service).quick());
            worker.release.countDown();
            assertTrue(worker.finished.await(3, TimeUnit.SECONDS));
        } finally {
            worker.release.countDown();
        }
    }

    @Test
    public void cancellingAnInFlightCallReturnsItsConcurrencyPermit() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.setQueueMinPoolSize(2);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.setMaximumRequest(1).setSendLimitPolicy(SendLimitPolicy.Reject);
        outgoing.connector("tcp").protocol("rsf");
        Worker worker = new Worker();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Work.class, worker).register();
            MetBindInfo<Work> service = client.publisher().rsfService(Work.class).timeout(5000).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));

            // 首个请求占据唯一的在途配额，后续请求应被明确拒绝。
            MetFuture waiting = remote.asyncInvoke(service, "waitForRelease", new Class<?>[0], new Object[0]);
            assertTrue(worker.entered.await(3, TimeUnit.SECONDS));
            Throwable rejected = failure(remote.asyncInvoke(service, "quick", new Class<?>[0], new Object[0]));
            assertTrue(rejected.toString(), rejected instanceof MetException);
            assertFalse(rejected instanceof MetTimeoutException);

            // 取消释放客户端请求资源，不承诺终止服务端正在执行的 Java 方法。
            assertTrue(waiting.cancel());
            assertTrue(waiting.isCancelled());
            assertEquals("ready", remote.getRemote(service).quick());
            worker.release.countDown();
            assertTrue(worker.finished.await(3, TimeUnit.SECONDS));
        } finally {
            worker.release.countDown();
        }
    }

    private static Throwable failure(MetFuture future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.getData(5, TimeUnit.SECONDS));
        return failure.getCause();
    }

    public interface Work {
        String quick();

        String fail();

        String waitForRelease() throws InterruptedException;
    }

    public static class Worker implements Work {
        private final CountDownLatch entered  = new CountDownLatch(1);
        private final CountDownLatch release  = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        public String quick() {
            return "ready";
        }

        public String fail() {
            throw new IllegalStateException("inventory unavailable");
        }

        public String waitForRelease() throws InterruptedException {
            this.entered.countDown();
            try {
                assertTrue(this.release.await(10, TimeUnit.SECONDS));
                return "released";
            } finally {
                this.finished.countDown();
            }
        }
    }
}
