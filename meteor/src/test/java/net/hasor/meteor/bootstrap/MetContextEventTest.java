/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.*;
import net.hasor.meteor.domain.MetServiceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContextEventTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void lifecycleSubscribersCanCoordinateWithAnotherThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger completed = new AtomicInteger();
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.subscribe(event -> {
                Callable<Void> action;
                if (event.getType() == MetContextEventType.STARTED) {
                    action = () -> {
                        context.offline();
                        return null;
                    };
                } else if (event.getType() == MetContextEventType.CLOSED) {
                    action = () -> {
                        context.close();
                        return null;
                    };
                } else {
                    return;
                }
                try {
                    // 同步事件回调等待另一个线程操作上下文，不应互相等待生命周期锁。
                    worker.submit(action).get(5, TimeUnit.SECONDS);
                    completed.incrementAndGet();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            context.start();
            assertFalse(context.isOnline());
            assertEquals(1, completed.get());
            context.close();
            assertEquals(2, completed.get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void reportsCommittedLifecycleAndServiceEventsAndIsolatesListeners() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.setAutomaticOnline(false);
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            Cancellable broken = context.subscribe(event -> {
                throw new IllegalStateException("listener failure");
            });
            context.subscribe(events::add);
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();
            assertEquals(service.getBindID(), events.get(0).getServiceId());
            assertEquals(service.getBindID(), context.getServiceInfo(events.get(0).getServiceId()).getBindID());
            assertEquals(MetServiceType.Provider, events.get(0).getServiceInfo().getServiceType());
            context.start();
            context.start();
            context.online();
            context.online();
            context.offline();
            context.offline();

            // 运行期新增消费者，使用相同的登记事件，通过服务信息判断身份。
            MetBindInfo<Echo> late = context.publisher().rsfService(Echo.class).name("late").register();
            assertEquals(late.getBindID(), events.get(4).getServiceId());
            assertEquals(MetServiceType.Consumer, events.get(4).getServiceInfo().getServiceType());
            assertTrue(broken.cancel());
            assertFalse(broken.cancel());
            context.close();
            context.close();

            List<MetContextEventType> expected = List.of(MetContextEventType.REGISTERED, MetContextEventType.STARTED, MetContextEventType.ONLINE, MetContextEventType.OFFLINE, MetContextEventType.REGISTERED, MetContextEventType.CLOSED);
            assertEquals(expected, events.stream().map(MetContextEvent::getType).toList());
            for (MetContextEvent event : events) {
                if (event.getType() != MetContextEventType.REGISTERED) {
                    assertNull(event.getServiceId());
                    assertNull(event.getServiceInfo());
                }
            }
            assertThrows(IllegalStateException.class, () -> context.subscribe(events::add));
        }
    }

    @Test
    public void cancellingOneRegistrationDoesNotRemoveAnotherForTheSameListener() throws Exception {
        try (MetContext context = new MetConfiguration().buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            MetContextListener listener = events::add;
            Cancellable first = context.subscribe(listener);
            Cancellable second = context.subscribe(listener);
            assertTrue(first.cancel());
            assertFalse(first.cancel());
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            assertEquals(1, events.size());
            assertEquals(MetContextEventType.REGISTERED, events.get(0).getType());
            assertEquals(service.getBindID(), events.get(0).getServiceId());
            assertTrue(second.cancel());
        }
    }

    @Test
    public void subscriptionsBelongToOneContextAndAreReleasedOnClose() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext first = configuration.buildContext(); //
             MetContext second = configuration.buildContext()) {
            List<MetContextEvent> firstEvents = new ArrayList<>();
            List<MetContextEvent> secondEvents = new ArrayList<>();
            Cancellable firstSubscription = first.subscribe(firstEvents::add);
            second.subscribe(secondEvents::add);

            // 同一配置创建的上下文独立分发事件，不共享监听器。
            first.publisher().rsfService(Echo.class).register();
            assertEquals(1, firstEvents.size());
            assertTrue(secondEvents.isEmpty());

            // 关闭通知只发送一次，关闭后订阅已释放，另一上下文仍可正常通知。
            first.close();
            first.close();
            assertEquals(2, firstEvents.size());
            assertEquals(MetContextEventType.CLOSED, firstEvents.get(1).getType());
            assertFalse(firstSubscription.cancel());
            second.publisher().rsfService(Echo.class).register();
            assertEquals(1, secondEvents.size());
            assertEquals(2, firstEvents.size());
        }
    }

}
