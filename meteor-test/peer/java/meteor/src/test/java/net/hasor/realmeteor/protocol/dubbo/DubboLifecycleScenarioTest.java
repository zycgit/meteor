/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.example.protocol.dubbo.DubboTaskService;
import net.hasor.example.protocol.dubbo.DubboTaskServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 普通调用、oneway、取消和关闭遵循独立的生命周期。 */
@NxnRpc(name = "dubbo")
public class DubboLifecycleScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void ordinaryCallRemainsPendingUntilBusinessCompletes() throws Exception {
        // 应用自己控制业务完成时机，协议只传输请求和响应。
        DubboTaskServiceImpl implementation = new DubboTaskServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboTaskService.class, implementation).register();
            MetBindInfo<DubboTaskService> service = client.publisher().rsfService(DubboTaskService.class).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 普通请求即使已经进入业务，也不能把本地写出当作最终结果。
            MetFuture pending = caller.asyncInvoke(service, "execute", new Class<?>[] { String.class }, //
                    new Object[] { "completed" });
            try {
                assertTrue(implementation.getEntered().await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.getData(100, TimeUnit.MILLISECONDS));
                assertFalse(pending.getRequest().isMessage());
            } finally {
                implementation.getRelease().countDown();
            }

            // 业务返回后调用方才取得真实结果。
            assertEquals("completed", pending.getData(3, TimeUnit.SECONDS));
            assertTrue(implementation.getCompleted().await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void messageCompletesAfterWriteWhileBusinessIsStillRunning() throws Exception {
        // 应用自己控制业务完成时机，协议只传输请求和响应。
        DubboTaskServiceImpl implementation = new DubboTaskServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboTaskService.class, implementation).register();
            MetBindInfo<DubboTaskService> service = client.publisher().rsfService(DubboTaskService.class).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 方法注解选择 Dubbo oneway；Future 只承诺完整写出，不等待业务返回。
            MetFuture pending = caller.asyncInvoke(service, "notify", new Class<?>[] { String.class }, //
                    new Object[] { "notification" });
            try {
                MetResponse response = pending.get(3, TimeUnit.SECONDS);
                assertTrue(pending.getRequest().isMessage());
                assertNull(response.getData());
                assertTrue(implementation.getEntered().await(3, TimeUnit.SECONDS));
                assertEquals(1L, implementation.getCompleted().getCount());
            } finally {
                implementation.getRelease().countDown();
            }

            // 写出确认不会取消远端消息业务，显式释放后它仍执行完毕。
            assertTrue(implementation.getCompleted().await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellationDoesNotPoisonTheConnection() throws Exception {
        // 应用自己控制业务完成时机，协议只传输请求和响应。
        DubboTaskServiceImpl implementation = new DubboTaskServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboTaskService.class, implementation).register();
            MetBindInfo<DubboTaskService> service = client.publisher().rsfService(DubboTaskService.class).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 等待目标业务确实进入后取消当前 Future。
            MetFuture pending = caller.asyncInvoke(service, "execute", new Class<?>[] { String.class }, //
                    new Object[] { "cancelled" });
            try {
                assertTrue(implementation.getEntered().await(3, TimeUnit.SECONDS));
                assertTrue(pending.cancel());
                assertTrue(pending.isCancelled());
                assertTrue(pending.isDone());
            } finally {
                implementation.getRelease().countDown();
            }
            assertTrue(implementation.getCompleted().await(3, TimeUnit.SECONDS));

            // 迟到响应只属于已取消请求；新请求仍与自己的请求 ID 对应。
            DubboTaskService remote = caller.getRemote(service);
            assertEquals("after cancellation", remote.echo("after cancellation"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void deadlineDoesNotPoisonFollowingCalls() throws Exception {
        // 应用自己控制业务完成时机，协议只传输请求和响应。
        DubboTaskServiceImpl implementation = new DubboTaskServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 500).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboTaskService.class, implementation).register();
            MetBindInfo<DubboTaskService> service = client.publisher().rsfService(DubboTaskService.class).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 业务保持阻塞直到调用方期限耗尽，验证的是 RPC 超时而不是测试等待超时。
            MetFuture pending = caller.asyncInvoke(service, "execute", new Class<?>[] { String.class }, //
                    new Object[] { "deadline" });
            try {
                assertTrue(implementation.getEntered().await(3, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                implementation.getRelease().countDown();
            }
            assertTrue(implementation.getCompleted().await(3, TimeUnit.SECONDS));

            // 超时请求的迟到结果不影响后续正常调用。
            DubboTaskService remote = caller.getRemote(service);
            assertEquals("after deadline", remote.echo("after deadline"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void closingTheClientTerminatesItsOutstandingCall() throws Exception {
        // 应用自己控制业务完成时机，协议只传输请求和响应。
        DubboTaskServiceImpl implementation = new DubboTaskServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboTaskService.class, implementation).register();
            MetBindInfo<DubboTaskService> service = client.publisher().rsfService(DubboTaskService.class).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 关闭前先确定调用已经抵达远端，避免只测试尚未发出的请求。
            MetFuture pending = caller.asyncInvoke(service, "execute", new Class<?>[] { String.class }, //
                    new Object[] { "closing" });
            try {
                assertTrue(implementation.getEntered().await(3, TimeUnit.SECONDS));
                client.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertFalse(//
                        failure.getCause() instanceof MetTimeoutException, "Closing should terminate immediately, not wait for RPC timeout");
            } finally {
                implementation.getRelease().countDown();
            }
            assertTrue(implementation.getCompleted().await(3, TimeUnit.SECONDS));
        }
    }

    private MetConfiguration configuration(boolean listener, int timeout) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(timeout);
        var tcp = configuration.connector("tcp");
        if (listener) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("dubbo");
        return configuration;
    }
}
