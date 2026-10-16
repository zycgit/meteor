/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeTestAccess;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketHandshakeUtils;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** 升级通道的 actor 提交顺序与 transport 终止契约。 */
public class HttpUpgradeChannelTest {
    @Test
    public void cancellingPendingUpgradeTerminatesItsExchangeBeforeReceiverCreation() {
        // 仅登记公开升级入口；尚无握手结果，也没有可交付的升级通道。
        HttpExchange exchange = WebSocketHandshakeUtils.newExchange("/ws", 2);
        AtomicInteger receivers = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        Future<NetworkChannel<WebSocketPayload>> upgraded = exchange.upgraded(WebSocketPayload.class, channel -> {
            receivers.incrementAndGet();
            throw new AssertionError("Cancelled handshake must not create a receiver");
        });
        exchange.completion().onFinal(done -> completions.incrementAndGet());
        assertFalse(upgraded.isDone());
        assertFalse(exchange.completion().isDone());
        assertFalse(exchange.response().isDone());

        // 取消 pending Future 必须同步终止 exchange，响应等待也收到同一个取消原因。
        assertTrue(upgraded.cancel());
        assertTrue(upgraded.isDone());
        assertTrue(upgraded.isCancelled());
        assertNull(upgraded.getResult());
        assertTrue(exchange.completion().isDone());
        assertTrue(exchange.response().isDone());
        Throwable failure = exchange.completion().getCause();
        assertTrue(failure instanceof CancellationException);
        assertSame(failure, exchange.response().getCause());
        assertEquals(0, receivers.get());

        // 重复取消和迟到的普通完成不会覆盖取消原因，也不会重复通知终止。
        assertFalse(upgraded.cancel());
        exchange.complete(null);
        assertSame(failure, exchange.completion().getCause());
        assertEquals(1, completions.get());
    }

    @Test
    public void transportFailurePreservesItsCauseOnExchangeCompletion() {
        // 物理 transport 的关闭信号保留原始异常，不重新生成泛化关闭原因。
        HttpExchange exchange = WebSocketHandshakeUtils.newExchange("/ws", 1);
        AtomicReference<Throwable> received = new AtomicReference<>();
        exchange.upgraded(String.class, channel -> new ChannelListener<String>() {
            @Override
            public void connected() {
            }

            @Override
            public void receive(String message) {
            }

            @Override
            public void closed(Throwable cause) {
                received.set(cause);
            }
        });
        HttpUpgradeTestAccess.attach(exchange, String.class, Runnable::run, message -> new BasicFuture<>(), () -> {
        });

        // Bridge 报告失败后，exchange 和应用 listener 观察到同一个 cause。
        IOException failure = new IOException("Physical connection failed");
        HttpUpgradeTestAccess.terminated(exchange, failure);
        assertSame(failure, received.get());
        assertSame(failure, exchange.completion().getCause());
    }

    @Test
    public void remoteStreamTerminationNotifiesOnceWithoutSendingAnotherReset() {
        // closeAction 代表本地主动 RST；远端已经终止 stream 时不应再次执行。
        HttpExchange exchange = WebSocketHandshakeUtils.newExchange("/ws", 2);
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        exchange.upgraded(String.class, channel -> new ChannelListener<String>() {
            @Override
            public void connected() {
            }

            @Override
            public void receive(String message) {
            }

            @Override
            public void closed(Throwable cause) {
                notifications.incrementAndGet();
            }
        });
        NetworkChannel<String> channel = HttpUpgradeTestAccess.attach(exchange, String.class, Runnable::run, //
                message -> new BasicFuture<>(), resets::incrementAndGet);

        // 重复的 stream 结束和随后的本地 close 都应幂等。
        IOException reset = new IOException("Remote HTTP/2 stream reset");
        HttpUpgradeTestAccess.terminated(exchange, reset);
        HttpUpgradeTestAccess.terminated(exchange, reset);
        channel.close();
        assertEquals(1, notifications.get());
        assertEquals(0, resets.get());
        assertSame(reset, exchange.completion().getCause());
    }

    @Test(timeout = 10000)
    public void drainCannotOvertakeAnAdmittedWriteBeforeExecutorSubmission() throws Exception {
        // 第一次 execute 在真正入队前暂停，重现 write 已接纳但 actor 尚不可见的交错。
        ControlledExecutor executor = new ControlledExecutor();
        BasicFuture<Void> physical = new BasicFuture<>();
        AtomicInteger closes = new AtomicInteger();
        HttpExchange exchange = WebSocketHandshakeUtils.newExchange("/ws", 1);
        NetworkChannel<String> channel = HttpUpgradeTestAccess.attach(exchange, String.class, executor, //
                message -> physical, closes::incrementAndGet);
        AtomicReference<Future<Void>> sent = new AtomicReference<>();
        Thread writing = new Thread(() -> sent.set(channel.write("admitted")), "upgrade-write");
        Thread draining = new Thread(channel::drainAndClose, "upgrade-drain");
        try {
            writing.start();
            assertTrue(executor.submitting.await(3, TimeUnit.SECONDS));
            draining.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (draining.isAlive() && draining.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.BLOCKED, draining.getState());
        } finally {
            executor.resume.countDown();
            writing.join(3000);
            draining.join(3000);
        }

        // actor 先处理 write 再处理 drain，物理写完成前不能执行关闭动作。
        assertFalse(writing.isAlive());
        assertFalse(draining.isAlive());
        executor.runQueued();
        assertFalse(sent.get().isDone());
        assertEquals(0, closes.get());
        physical.completed(null);
        executor.runQueued();
        assertTrue(sent.get().isDone());
        assertNull(sent.get().getCause());
        assertEquals(1, closes.get());
        assertTrue(exchange.completion().isDone());
    }

    private static final class ControlledExecutor implements Executor {
        private final Queue<Runnable> tasks      = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean   first      = new AtomicBoolean(true);
        private final CountDownLatch  submitting = new CountDownLatch(1);
        private final CountDownLatch  resume     = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            if (this.first.compareAndSet(true, false)) {
                this.submitting.countDown();
                try {
                    if (!this.resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Unit executor submission was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            this.tasks.add(command);
        }

        private void runQueued() {
            Runnable task;
            while ((task = this.tasks.poll()) != null) {
                task.run();
            }
        }
    }
}
