/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.AbstractMetListen;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.MetListen;
import org.junit.Test;
import static org.junit.Assert.*;

/** 连接器绑定状态机：挂起绑定、取消、失败重试及关闭回调。 */
public class MetListenTest {
    private EndpointFixture.Endpoint endpoint(EndpointFixture fixture) throws Exception {
        // 创建端点但不监听，用可控 Future 模拟传输层异步完成。
        fixture.manager.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS);
        EndpointFixture.Endpoint endpoint = fixture.endpoints.get("tcp");
        endpoint.binding = new BasicFuture<>();
        return endpoint;
    }

    @Test
    public void pendingBindsShareFutureAndCompleteOnlyWhenUsable() throws Exception {
        // 同一端点连续绑定，传输尚未完成时复用同一个挂起结果。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> first = endpoint.bind();
            assertSame(first, endpoint.bind());
            assertFalse(first.isDone());

            // 提供者交付可用监听器后，调用方得到该句柄。
            EndpointFixture.Listen listen = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(listen);
            assertSame(listen, first.get(5, TimeUnit.SECONDS));
            assertSame(first, endpoint.bind());
            assertEquals(1, endpoint.binds);
            assertSame(listen, endpoint.getListenList().get(0));
        }
    }

    @Test
    public void cancellationReclaimsLateListenerAndAllowsRetry() throws Exception {
        // 先取消调用方的绑定，再发起新的绑定以验证迟到资源隔离。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> cancelled = endpoint.bind();
            BasicFuture<MetListen> operation = endpoint.binding;
            cancelled.cancel();
            endpoint.binding = new BasicFuture<>();
            Future<MetListen> retry = endpoint.bind();
            EndpointFixture.Listen rejected = new EndpointFixture.Listen(endpoint, fixture.manager);

            // 旧绑定的迟到完成只回收旧监听器，不影响新绑定。
            operation.completed(rejected);
            assertFalse(rejected.isActive());
            assertFalse(retry.isDone());

            // 提供者交付可用监听器后，调用方得到该句柄。
            EndpointFixture.Listen listen = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(listen);
            assertSame(listen, retry.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void failedBindCanRetryWithoutReinitializingConnector() throws Exception {
        // 提供者返回绑定失败，端点自身保持可重试。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> result = endpoint.bind();
            IOException failure = new IOException("bind failed");
            endpoint.binding.failed(failure);
            assertSame(failure, result.getCause());

            // 新绑定可以成功，失败不污染连接器后续的绑定状态。
            endpoint.binding = null;
            MetListen retried = endpoint.bind().get(5, TimeUnit.SECONDS);
            assertTrue(retried.isActive());
            assertEquals(1, endpoint.starts);
        }
    }

    @Test
    public void cancelledProviderBindAllowsRetryWithoutReinitialization() throws Exception {
        // 提供者主动取消异步绑定，取消状态传递给调用方。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> pending = endpoint.bind();
            endpoint.binding.cancel();
            assertTrue(pending.isCancelled());
            assertTrue(endpoint.getListenList().isEmpty());

            // 新绑定可以成功，失败不污染连接器后续的绑定状态。
            endpoint.binding = null;
            MetListen retried = endpoint.bind().get(5, TimeUnit.SECONDS);
            assertTrue(retried.isActive());
            assertEquals(1, endpoint.starts);
        }
    }

    @Test
    public void missingProviderListenerFailsAndCanBeRetried() throws Exception {
        // 提供者错误地返回空监听器，结果必须明确失败。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> pending = endpoint.bind();
            endpoint.binding.completed(null);
            assertTrue(pending.getCause() instanceof IllegalStateException);
            assertTrue(endpoint.getListenList().isEmpty());

            // 新绑定可以成功，失败不污染连接器后续的绑定状态。
            endpoint.binding = null;
            MetListen retried = endpoint.bind().get(5, TimeUnit.SECONDS);
            assertTrue(retried.isActive());
        }
    }

    @Test
    public void closeBindRejectsLateCompletionAndFurtherBinds() throws Exception {
        // 绑定尚未完成时关闭监听能力，观察迟到监听器的释放。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> result = endpoint.bind();

            // 关闭动作同步拒绝挂起操作，并在释放监视器后通知回调。
            endpoint.closeBind();
            assertNotNull(result.getCause());
            EndpointFixture.Listen rejected = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(rejected);
            assertFalse(rejected.isActive());
            assertTrue(endpoint.getListenList().isEmpty());

            // 后续绑定被拒绝，但出站连接仍然可用。
            Throwable rejectedBind = fixture.manager.bind("tcp").getCause();
            assertTrue(rejectedBind instanceof IllegalStateException);
            MetChannel outgoing = endpoint.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS);
            assertTrue(outgoing.isActive());
        }
    }

    @Test
    public void closeRejectsPendingBindAndReleasesTransportOnce() throws Exception {
        // 连接器仍有挂起绑定时重复关闭，检查幂等释放。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> pending = endpoint.bind();

            // 同一传输关闭两次也只能触发一次实际释放。
            endpoint.close();
            endpoint.close();
            assertNotNull(pending.getCause());
            long closedCount = fixture.events.stream() //
                    .filter(event -> StringUtils.equals("tcp:closed", event)) //
                    .count();
            assertEquals(1, closedCount);
        }
    }

    @Test
    public void completionCallbacksRunOutsideConnectorMonitor() throws Exception {
        // 在绑定结果注册回调，记录完成时是否持有连接器锁。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> result = endpoint.bind();
            AtomicBoolean held = new AtomicBoolean(true);
            result.onFinal(done -> held.set(Thread.holdsLock(endpoint)));

            // 完成提供者操作后等待订阅的最终结果，再检查回调锁状态。
            endpoint.binding.completed(new EndpointFixture.Listen(endpoint, fixture.manager));
            result.get(1, TimeUnit.SECONDS);
            assertFalse(held.get());
        }
    }

    @Test
    public void failedCallbacksRunOutsideConnectorMonitorDuringClose() throws Exception {
        // 挂起绑定的失败回调记录关闭时是否持有连接器锁。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> result = endpoint.bind();
            AtomicBoolean held = new AtomicBoolean(true);
            result.onFailed(done -> held.set(Thread.holdsLock(endpoint)));

            // 关闭动作同步拒绝挂起操作，并在释放监视器后通知回调。
            endpoint.closeBind();
            assertNotNull(result.getCause());
            assertFalse(held.get());
        }
    }

    @Test
    public void wrongListenerTypeIsRejectedAndClosed() throws Exception {
        // 提供者返回类型错误的监听器，检查校验和资源释放。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<MetListen> result = endpoint.bind();
            AtomicBoolean closed = new AtomicBoolean();
            MetListen wrongType = new AbstractMetListen("wrong", endpoint.config().address(), fixture.manager) {
                public boolean isActive() {
                    return !closed.get();
                }

                public void close() {
                    closed.set(true);
                }
            };

            // 错误句柄立即关闭，不进入端点的有效监听器集合。
            endpoint.binding.completed(wrongType);
            assertNotNull(result.getCause());
            assertTrue(closed.get());
            assertTrue(endpoint.getListenList().isEmpty());
        }
    }

    @Test
    public void closedListenerCanBeBoundAgainButOnlyAtConfiguredAddress() throws Exception {
        // 显式关闭监听句柄后允许再次绑定相同配置地址。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            MetListen first = fixture.manager.bind("tcp").get(5, TimeUnit.SECONDS);

            // 关闭句柄再重新绑定，获得新对象但地址与所有权不变。
            first.close();
            MetListen second = fixture.manager.bind("tcp").get(5, TimeUnit.SECONDS);
            assertNotSame(first, second);
            assertEquals(first.getBindAddress(), second.getBindAddress());
            assertEquals(1, fixture.endpoints.get("tcp").getListenList().size());
        }
    }
}
