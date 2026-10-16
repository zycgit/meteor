/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.bootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContextEventTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void startedSubscriberCanRequestOfflineFromAnotherThread() throws Exception {
        // 工作线程等待启动事件，再执行回调安排的下线操作。
        MetConfiguration configuration = new MetConfiguration();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch requestOffline = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            Future<?> transition = worker.submit(() -> {
                await(requestOffline);
                context.offline();
                completed.incrementAndGet();
            });
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.STARTED) {
                    requestOffline.countDown();
                }
            });

            // 监听器不占用生命周期锁，工作线程最终完成下线及事件投递。
            context.start();
            transition.get(5, TimeUnit.SECONDS);
            assertFalse(context.isOnline());
            assertEquals(1, completed.get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void closedSubscriberCanRepeatCloseFromAnotherThread() throws Exception {
        // 关闭事件回调同步等待另一线程重复关闭，不能与关闭锁形成互相等待。
        MetConfiguration configuration = new MetConfiguration();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger completed = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.subscribe(event -> {
                if (event.getType() != MetContextEventType.CLOSED) {
                    return;
                }
                try {
                    // 已进入 CLOSED 后，重复关闭保持幂等并立即返回。
                    worker.submit(() -> {
                        context.close();
                        return null;
                    }).get(5, TimeUnit.SECONDS);
                    completed.incrementAndGet();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            context.start();

            // 外层关闭完成后只处理一次关闭事件。
            context.close();
            assertEquals(1, completed.get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void registrationEventContainsCommittedProviderInformation() throws Exception {
        // 在构建后订阅服务事件，通过公开发布入口登记提供者。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            context.subscribe(events::add);
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();

            // 收到登记事件时，服务查询已经可见，事件明确携带提供者身份。
            assertEquals(1, events.size());
            MetContextEvent event = events.get(0);
            assertEquals(MetContextEventType.REGISTERED, event.getType());
            assertEquals(service.getBindID(), event.getServiceId());
            MetBindInfo<?> committed = context.getServiceInfo(event.getServiceId());
            assertEquals(service.getBindID(), committed.getBindID());
            assertEquals(MetServiceType.Provider, event.getServiceInfo().getServiceType());
        }
    }

    @Test
    public void registrationEventIdentifiesConsumerPublishedAfterStartup() throws Exception {
        // 上下文已启动，随后登记没有实现对象的消费者。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            List<MetContextEvent> events = new ArrayList<>();
            context.subscribe(events::add);
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).name("late").register();

            // 运行期间的消费者使用同一登记事件，并由服务信息区分身份。
            assertEquals(1, events.size());
            MetContextEvent event = events.get(0);
            assertEquals(MetContextEventType.REGISTERED, event.getType());
            assertEquals(service.getBindID(), event.getServiceId());
            assertEquals(MetServiceType.Consumer, event.getServiceInfo().getServiceType());
        }
    }

    @Test
    public void lifecycleEventsFollowCommittedTransitionsOnlyOnce() throws Exception {
        // 关闭自动上线，明确观察启动、上线、下线和关闭的状态迁移。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.setAutomaticOnline(false);
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            context.subscribe(events::add);

            // 每个状态重复操作不重复通知，最终事件顺序对应已完成的迁移。
            context.start();
            context.start();
            context.online();
            context.online();
            context.offline();
            context.offline();
            context.close();
            context.close();
            List<MetContextEventType> expected = List.of( //
                    MetContextEventType.STARTED, MetContextEventType.ONLINE, //
                    MetContextEventType.OFFLINE, MetContextEventType.CLOSED);
            List<MetContextEventType> actual = events.stream().map(MetContextEvent::getType).toList();
            assertEquals(expected, actual);
            for (MetContextEvent event : events) {
                assertNull(event.getServiceId());
                assertNull(event.getServiceInfo());
            }
        }
    }

    @Test
    public void lifecycleListenerTransitionsAreDeliveredAfterTheCurrentEvent() throws Exception {
        // 手动上线后，第一个监听者在 ONLINE 回调中立即发起下线。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.setAutomaticOnline(false);
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEventType> observed = new ArrayList<>();
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.ONLINE) {
                    context.offline();
                }
            });
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.ONLINE || event.getType() == MetContextEventType.OFFLINE) {
                    observed.add(event.getType());
                }
            });

            // 当前 ONLINE 事件必须先完成全部投递，再投递回调中提交的 OFFLINE。
            context.start();
            context.online();
            assertFalse(context.isOnline());
            assertEquals(List.of(MetContextEventType.ONLINE, MetContextEventType.OFFLINE), observed);
        }
    }

    @Test
    public void concurrentLifecycleCallWaitsForItsEventDelivery() throws Exception {
        // ONLINE 投递线程停在监听器中，另一线程在此期间提交 OFFLINE。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.setAutomaticOnline(false);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch onlineEntered = new CountDownLatch(1);
        CountDownLatch releaseOnline = new CountDownLatch(1);
        CountDownLatch offlineEntered = new CountDownLatch(1);
        CountDownLatch releaseOffline = new CountDownLatch(1);
        CountDownLatch offlineReturned = new CountDownLatch(1);
        try (MetContext context = configuration.buildContext()) {
            try {
                List<MetContextEventType> observed = new ArrayList<>();
                context.subscribe(event -> {
                    if (event.getType() == MetContextEventType.ONLINE) {
                        observed.add(event.getType());
                        onlineEntered.countDown();
                        await(releaseOnline);
                    } else if (event.getType() == MetContextEventType.OFFLINE) {
                        observed.add(event.getType());
                        offlineEntered.countDown();
                        await(releaseOffline);
                    }
                });
                context.start();

                Future<?> onlineCall = workers.submit(context::online);
                assertTrue(onlineEntered.await(5, TimeUnit.SECONDS));
                assertFalse(onlineCall.isDone());
                Future<?> offlineCall = workers.submit(() -> {
                    context.offline();
                    offlineReturned.countDown();
                });

                // OFFLINE 已提交，但其调用线程必须等待当前 ONLINE 投递结束。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (context.isOnline() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertFalse(context.isOnline());
                assertFalse(offlineReturned.await(200, TimeUnit.MILLISECONDS));

                // OFFLINE 回调本身未完成时，提交它的线程仍不能提前返回。
                releaseOnline.countDown();
                assertTrue(offlineEntered.await(5, TimeUnit.SECONDS));
                assertFalse(offlineReturned.await(200, TimeUnit.MILLISECONDS));
                releaseOffline.countDown();
                onlineCall.get(5, TimeUnit.SECONDS);
                offlineCall.get(5, TimeUnit.SECONDS);
                assertEquals(List.of(MetContextEventType.ONLINE, MetContextEventType.OFFLINE), observed);
            } finally {
                releaseOnline.countDown();
                releaseOffline.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    public void failingListenerDoesNotPreventLaterListenerDelivery() throws Exception {
        // 先订阅抛异常的监听器，再订阅正常监听器。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            Cancellable broken = context.subscribe(event -> {
                throw new IllegalStateException("listener failure");
            });
            context.subscribe(events::add);

            // 前一个监听器失败不影响服务登记结果和后续监听器通知。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            assertEquals(1, events.size());
            assertEquals(service.getBindID(), events.get(0).getServiceId());
            assertTrue(broken.cancel());
            assertFalse(broken.cancel());
        }
    }

    @Test
    public void closedContextRejectsNewSubscriptions() throws Exception {
        // 关闭后事件资源已经释放，不能登记新的监听器。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.close();
            assertThrows(IllegalStateException.class, () -> context.subscribe(event -> {
            }));
        }
    }

    @Test
    public void cancellingOneRegistrationDoesNotRemoveAnotherForTheSameListener() throws Exception {
        // 同一监听器订阅两次，每个取消句柄只负责它自己的登记。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            MetContextListener listener = events::add;
            Cancellable first = context.subscribe(listener);
            Cancellable second = context.subscribe(listener);
            assertTrue(first.cancel());
            assertFalse(first.cancel());

            // 取消第一个订阅后，发布事件仍通过第二个订阅送达一次。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            assertEquals(1, events.size());
            assertEquals(MetContextEventType.REGISTERED, events.get(0).getType());
            assertEquals(service.getBindID(), events.get(0).getServiceId());
            assertTrue(second.cancel());
        }
    }

    @Test
    public void subscriptionsBelongToOneContextAndAreReleasedOnClose() throws Exception {
        // 同一配置构建两个上下文，分别登记监听器用于验证关闭隔离。
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for lifecycle event coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

}
