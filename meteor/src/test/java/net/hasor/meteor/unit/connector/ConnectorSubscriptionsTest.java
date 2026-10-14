/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorSubscriptionsTest {
    @Test
    public void dispatchesRequestInRegistrationOrderWithoutManagerLock() {
        // 注册两个订阅者，保留原始请求对象、来源通道和分发线程。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            RequestPayload payload = new RequestPayload();
            MetChannel source = new TestChannel();
            List<String> received = new ArrayList<>();
            Thread deliveryThread = Thread.currentThread();
            manager.subscribe((channel, id, delivered) -> {
                // 回调必须收到原始对象，且执行时不持有管理器监视器。
                assertSame(source, channel);
                assertEquals(42, id);
                assertSame(payload, delivered);
                assertSame(deliveryThread, Thread.currentThread());
                assertFalse(Thread.holdsLock(manager));
                received.add("first");
            });
            manager.subscribe((channel, id, delivered) -> received.add("second"));

            // 只分发一次请求，订阅者按注册顺序同步执行。
            manager.onRequest(source, 42, payload);
            assertEquals(Arrays.asList("first", "second"), received);
        }
    }

    @Test
    public void dispatchesResponseInRegistrationOrderWithoutManagerLock() {
        // 注册两个订阅者，保留原始响应对象、来源通道和分发线程。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            ResponsePayload payload = new ResponsePayload();
            MetChannel source = new TestChannel();
            List<String> received = new ArrayList<>();
            Thread deliveryThread = Thread.currentThread();
            manager.subscribe((channel, id, delivered) -> {
                // 回调必须收到原始对象，且执行时不持有管理器监视器。
                assertSame(source, channel);
                assertEquals(42, id);
                assertSame(payload, delivered);
                assertSame(deliveryThread, Thread.currentThread());
                assertFalse(Thread.holdsLock(manager));
                received.add("first");
            });
            manager.subscribe((channel, id, delivered) -> received.add("second"));

            // 只分发一次响应，订阅者按注册顺序同步执行。
            manager.onResponse(source, 42, payload);
            assertEquals(Arrays.asList("first", "second"), received);
        }
    }

    @Test
    public void dispatchesFailureInRegistrationOrderWithoutManagerLock() {
        // 注册两个订阅者，保留原始失败对象、来源通道和分发线程。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            ThrowPayload payload = new ThrowPayload(new IOException("disconnected"));
            MetChannel source = new TestChannel();
            List<String> received = new ArrayList<>();
            Thread deliveryThread = Thread.currentThread();
            manager.subscribe((channel, id, delivered) -> {
                // 回调必须收到原始对象，且执行时不持有管理器监视器。
                assertSame(source, channel);
                assertEquals(42, id);
                assertSame(payload, delivered);
                assertSame(deliveryThread, Thread.currentThread());
                assertFalse(Thread.holdsLock(manager));
                received.add("first");
            });
            manager.subscribe((channel, id, delivered) -> received.add("second"));

            // 只分发一次失败，订阅者按注册顺序同步执行。
            manager.onFailure(source, 42, payload);
            assertEquals(Arrays.asList("first", "second"), received);
        }
    }

    @Test
    public void repeatedRegistrationDeliversOncePerEntry() {
        // 同一订阅对象注册两次，每个注册项都接收一次通知。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            ConnectorSubscriber subscriber = (channel, id, payload) -> received.incrementAndGet();
            manager.subscribe(subscriber);
            manager.subscribe(subscriber);

            // 单条响应触发两个注册项，不能按对象身份去重。
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(2, received.get());
        }
    }

    @Test
    public void subscriberExceptionDoesNotPreventRequestDeliveryToPeers() {
        // 第一个订阅者抛错，第二个订阅者记录请求通知。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> {
                throw new IllegalStateException("observer");
            });
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());

            // 订阅者故障不截断同一条通知向后续注册项的分发。
            RequestPayload payload = new RequestPayload();
            manager.onRequest(new TestChannel(), 1, payload);
            assertEquals(1, received.get());
        }
    }

    @Test
    public void subscriberExceptionDoesNotPreventResponseDeliveryToPeers() {
        // 第一个订阅者抛错，第二个订阅者记录响应通知。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> {
                throw new IllegalStateException("observer");
            });
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());

            // 订阅者故障不截断同一条通知向后续注册项的分发。
            ResponsePayload payload = new ResponsePayload();
            manager.onResponse(new TestChannel(), 1, payload);
            assertEquals(1, received.get());
        }
    }

    @Test
    public void subscriberExceptionDoesNotPreventFailureDeliveryToPeers() {
        // 第一个订阅者抛错，第二个订阅者记录失败通知。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> {
                throw new IllegalStateException("observer");
            });
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());

            // 订阅者故障不截断同一条通知向后续注册项的分发。
            ThrowPayload payload = new ThrowPayload(new IOException("disconnected"));
            manager.onFailure(new TestChannel(), 1, payload);
            assertEquals(1, received.get());
        }
    }

    @Test
    public void registrationInsideCallbackOnlyAffectsFollowingNotifications() {
        // 回调期间注册第三个订阅者，用两次通知检查订阅快照边界。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            List<String> received = new ArrayList<>();
            manager.subscribe((channel, id, response) -> {
                received.add("first");
                manager.subscribe((nextChannel, nextId, next) -> received.add("new"));
            });
            manager.subscribe((channel, id, response) -> received.add("second"));

            // 当前通知只使用开始分发时的订阅快照。
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(Arrays.asList("first", "second"), received);

            // 新订阅者从下一次通知开始接收，原顺序保持不变。
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            List<String> expected = Arrays.asList("first", "second", "first", "second", "new");
            assertEquals(expected, received);
        }
    }

    @Test
    public void shutdownResponseRemainsDeliverableUntilCleanupFinishes() throws Exception {
        // 在真实连接器清理钩子中发送响应，随后模拟清理失败。
        ConnectorConfig config = EndpointFixture.config("closing");
        MetContext context = ConnectorTestSupport.sharedContext(config);
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            List<Payload> received = new ArrayList<>();
            ResponsePayload payload = new ResponsePayload();
            manager.prepare(config, (cfg, owner) -> new EndpointFixture.Endpoint(cfg, owner, new ArrayList<>()) {
                @Override
                protected void doClose() {
                    this.manager.onResponse(new TestChannel(), 1, payload);
                    throw new IllegalStateException("cleanup failure");
                }
            });
            manager.init();
            manager.subscribe((channel, id, delivered) -> received.add(delivered));
            manager.bind(config.name()).get(5, TimeUnit.SECONDS);

            // 清理尚未结束时仍分发终结通知，即使清理钩子随后抛错。
            manager.close();
            assertEquals(Collections.singletonList(payload), received);

            // 清理完成后订阅已移除，迟到通知不能再次进入旧订阅者。
            manager.onResponse(new TestChannel(), 1, payload);
            assertEquals(1, received.size());
        }
    }

    @Test
    public void shutdownFailureRemainsDeliverableUntilCleanupFinishes() throws Exception {
        // 在真实连接器清理钩子中发送失败，随后模拟清理失败。
        ConnectorConfig config = EndpointFixture.config("closing");
        MetContext context = ConnectorTestSupport.sharedContext(config);
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            List<Payload> received = new ArrayList<>();
            ThrowPayload payload = new ThrowPayload(new IOException("disconnected"));
            manager.prepare(config, (cfg, owner) -> new EndpointFixture.Endpoint(cfg, owner, new ArrayList<>()) {
                @Override
                protected void doClose() {
                    this.manager.onFailure(new TestChannel(), 1, payload);
                    throw new IllegalStateException("cleanup failure");
                }
            });
            manager.init();
            manager.subscribe((channel, id, delivered) -> received.add(delivered));
            manager.bind(config.name()).get(5, TimeUnit.SECONDS);

            // 清理尚未结束时仍分发终结通知，即使清理钩子随后抛错。
            manager.close();
            assertEquals(Collections.singletonList(payload), received);

            // 清理完成后订阅已移除，迟到通知不能再次进入旧订阅者。
            manager.onFailure(new TestChannel(), 1, payload);
            assertEquals(1, received.size());
        }
    }

    @Test
    public void closedManagerDoesNotDeliverLateRequest() {
        // 三个订阅项在关闭后均移除，重复初始化不改变它们的生命周期。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.init();
            manager.close();

            // 迟到的请求不能调用任何已关闭的订阅项。
            RequestPayload payload = new RequestPayload();
            manager.onRequest(new TestChannel(), 1, payload);
            assertEquals(0, received.get());
        }
    }

    @Test
    public void closedManagerDoesNotDeliverLateResponse() {
        // 三个订阅项在关闭后均移除，重复初始化不改变它们的生命周期。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.init();
            manager.close();

            // 迟到的响应不能调用任何已关闭的订阅项。
            ResponsePayload payload = new ResponsePayload();
            manager.onResponse(new TestChannel(), 1, payload);
            assertEquals(0, received.get());
        }
    }

    @Test
    public void closedManagerDoesNotDeliverLateFailure() {
        // 三个订阅项在关闭后均移除，重复初始化不改变它们的生命周期。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.init();
            manager.close();

            // 迟到的失败不能调用任何已关闭的订阅项。
            ThrowPayload payload = new ThrowPayload(new IOException("late failure"));
            manager.onFailure(new TestChannel(), 1, payload);
            assertEquals(0, received.get());
        }
    }

    @Test
    public void restartedManagerRequiresFreshSubscriptions() {
        // 重启前注册的订阅者应随关闭一起清除。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.init();
            AtomicInteger previous = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> previous.incrementAndGet());
            manager.close();

            // 重启后显式注册新订阅者，只向新注册项分发。
            manager.init();
            AtomicInteger fresh = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> fresh.incrementAndGet());
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(0, previous.get());
            assertEquals(1, fresh.get());
        }
    }

    private static final class TestChannel implements MetChannel {
        public MetConnector getConnector() {
            throw new UnsupportedOperationException();
        }

        public long getChannelId() {
            return 1;
        }

        public InterAddress getLocal() {
            throw new UnsupportedOperationException();
        }

        public InterAddress getRemote() {
            throw new UnsupportedOperationException();
        }

        public boolean isActive() {
            return true;
        }

        public BasicFuture<MetChannel> sendData(Payload payload) {
            return new BasicFuture<>(this);
        }

        public BasicFuture<MetChannel> close() {
            return new BasicFuture<>(this);
        }

        public BasicFuture<MetChannel> drainAndClose() {
            return this.close();
        }
    }
}
