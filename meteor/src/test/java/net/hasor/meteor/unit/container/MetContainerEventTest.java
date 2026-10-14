/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContextEvent;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetContextListener;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.unit.container.ContainerFixtures.Echo;
import net.hasor.meteor.domain.MetServiceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerEventTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void registrationSucceedsWithoutSubscriptions() {
        // 容器不需要先安装监听器，登记后即可查询服务。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertNotNull(this.container.getRsfBindInfo(service.getBindID()));
    }

    @Test
    public void providerRegistrationNotifiesCommittedStateOutsideContainerLock() {
        // 登记回调观察已提交的服务状态，并且不占用容器监视器。
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(event -> {
            assertFalse(Thread.holdsLock(this.container));
            assertSame(this.container.getRsfBindInfo(event.getServiceId()), event.getServiceInfo());
            events.add(event);
        });

        // 登记完成后只收到一次提供者事件。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, value -> value).register();
        assertEquals(1, events.size());
        MetContextEvent registered = events.get(0);
        assertEquals(MetContextEventType.REGISTERED, registered.getType());
        assertEquals(service.getBindID(), registered.getServiceId());
        assertEquals(MetServiceType.Provider, registered.getServiceInfo().getServiceType());
    }

    @Test
    public void providerRemovalNotifiesCommittedStateOutsideContainerLock() {
        // 移除回调只在服务已不可查询后执行，并且不占用容器监视器。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, value -> value).register();
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(event -> {
            assertFalse(Thread.holdsLock(this.container));
            assertNull(this.container.getRsfBindInfo(event.getServiceId()));
            events.add(event);
        });

        // 回收完成后只收到一次提供者移除事件。
        assertTrue(this.container.recoverService(service.getBindID()));
        assertEquals(1, events.size());
        MetContextEvent removed = events.get(0);
        assertEquals(MetContextEventType.REMOVED, removed.getType());
        assertEquals(service.getBindID(), removed.getServiceId());
        assertEquals(MetServiceType.Provider, removed.getServiceInfo().getServiceType());
    }

    @Test
    public void consumerRegistrationReportsConsumerType() {
        // 消费者的登记事件使用与提供者相同的事件类型，并保留消费者身份。
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(event -> {
            assertFalse(Thread.holdsLock(this.container));
            assertSame(this.container.getRsfBindInfo(event.getServiceId()), event.getServiceInfo());
            events.add(event);
        });

        // 登记回调同样观察已提交状态，且不持有容器锁。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(1, events.size());
        MetContextEvent registered = events.get(0);
        assertEquals(MetContextEventType.REGISTERED, registered.getType());
        assertEquals(service.getBindID(), registered.getServiceId());
        assertEquals(MetServiceType.Consumer, registered.getServiceInfo().getServiceType());
    }

    @Test
    public void consumerRemovalReportsConsumerType() {
        // 已登记消费者回收后报告一次移除事件，事件中保留原服务身份。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(event -> {
            assertFalse(Thread.holdsLock(this.container));
            assertNull(this.container.getRsfBindInfo(event.getServiceId()));
            events.add(event);
        });

        // 消费者移除也在提交之后发出通知。
        assertTrue(this.container.recoverService(service.getBindID()));
        assertEquals(1, events.size());
        MetContextEvent removed = events.get(0);
        assertEquals(MetContextEventType.REMOVED, removed.getType());
        assertEquals(service.getBindID(), removed.getServiceId());
        assertEquals(MetServiceType.Consumer, removed.getServiceInfo().getServiceType());
    }

    @Test
    public void removalListenerFailureDoesNotBlockOtherSubscriptions() {
        // 首个监听器在移除时抛错，后续监听器仍应收到此次事件。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        this.container.subscribe(event -> {
            throw new IllegalStateException("listener failure");
        });
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(events::add);

        // 首个监听器的异常不能阻止移除完成或后续通知。
        assertTrue(this.container.recoverService(service.getBindID()));
        assertNull(this.container.getRsfBindInfo(service.getBindID()));
        assertEquals(1, events.size());
        assertEquals(MetContextEventType.REMOVED, events.get(0).getType());
    }

    @Test
    public void duplicateConsumerRegistrationDoesNotNotifyAgain() {
        // 首次消费者登记成功后，重复登记失败不产生新的通知。
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(events::add);
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertThrows(IllegalStateException.class, () -> this.publisher.rsfService(Echo.class).register());
        assertEquals(1, events.size());
        assertEquals(service.getBindID(), events.get(0).getServiceId());
    }

    @Test
    public void repeatedConsumerRecoveryDoesNotNotifyAgain() {
        // 消费者已回收时再次回收返回 false，不重复发送移除通知。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(events::add);
        assertTrue(this.container.recoverService(service.getBindID()));
        assertFalse(this.container.recoverService(service.getBindID()));
        assertEquals(1, events.size());
        assertEquals(MetContextEventType.REMOVED, events.get(0).getType());
    }

    @Test
    public void consumerReplacementDoesNotRewriteEarlierProviderEvents() {
        // 先记录提供者的完整生命周期，再将同一 ID 声明为消费者。
        List<MetContextEvent> events = new ArrayList<>();
        this.container.subscribe(events::add);
        MetBindInfo<Echo> provider = this.publisher.rsfService(Echo.class, value -> value).register();
        assertTrue(this.container.recoverService(provider.getBindID()));
        MetBindInfo<Echo> consumer = this.publisher.rsfService(Echo.class).register();
        assertTrue(this.container.recoverService(consumer.getBindID()));

        // 新旧事件各保留原来的服务对象和角色，不会被后续登记改写。
        assertEquals(provider.getBindID(), consumer.getBindID());
        assertEquals(4, events.size());
        MetBindInfo<?> oldInfo = events.get(0).getServiceInfo();
        MetBindInfo<?> newInfo = events.get(2).getServiceInfo();
        assertSame(oldInfo, events.get(1).getServiceInfo());
        assertSame(newInfo, events.get(3).getServiceInfo());
        assertEquals(MetServiceType.Provider, oldInfo.getServiceType());
        assertEquals(MetServiceType.Consumer, newInfo.getServiceType());
        for (MetContextEvent event : events) {
            assertEquals(provider.getBindID(), event.getServiceId());
        }
    }

    @Test
    public void rejectedRegistrationIsWithdrawnAndCanBeRetried() throws Exception {
        // 校验监听器检查可查询状态后拒绝本次登记，另一个服务预先正常存在。
        AddressPool addresses = new AddressPool();
        MetContainer local = new MetContainer(addresses, this.settings);
        MetPublisher publisher = local.createPublisher();
        MetBindInfo<Echo> existing = publisher.rsfService(Echo.class).name("existing").register();
        IllegalArgumentException rejected = new IllegalArgumentException("invalid service");
        Cancellable validation = local.subscribe(event -> {
            if (event.getType() == MetContextEventType.REGISTERED) {
                // 复用已登记事件，校验者可以查询服务与地址，回调不占用容器锁。
                assertFalse(Thread.holdsLock(local));
                assertSame(event.getServiceInfo(), local.getRsfBindInfo(event.getServiceId()));
                assertNotNull(addresses.getBucket(event.getServiceId()));
                throw rejected;
            }
        });
        List<MetContextEventType> events = new ArrayList<>();
        local.subscribe(event -> events.add(event.getType()));
        var service = publisher.rsfService(Echo.class, value -> value);
        service.aliasName("test", "echo").bindAddress("rsf://localhost:2181/default");

        // 失败只撤销本次登记，不影响同一容器已有的其它服务。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
        assertSame(rejected, failure);
        String serviceId = "[default]" + Echo.class.getName() + "-1.0";
        assertNull(local.getRsfBindInfo(serviceId));
        assertNull(local.getRsfBindInfo("test", "echo"));
        assertNull(addresses.getBucket(serviceId));
        assertEquals(List.of(existing.getBindID()), local.getServiceIDs());
        assertNotNull(addresses.getBucket(existing.getBindID()));
        assertEquals(List.of(MetContextEventType.REMOVED), events);

        // 移除失败监听器后，同一服务及别名可以重新发布。
        assertTrue(validation.cancel());
        MetBindInfo<Echo> published = service.register();
        MetBindInfo<?> aliased = local.getRsfBindInfo("test", "echo");
        assertEquals(published.getBindID(), aliased.getBindID());
        assertNotNull(addresses.nextAddress(published.getBindID(), "echo", null));
        assertEquals(List.of(MetContextEventType.REMOVED, MetContextEventType.REGISTERED), events);
    }

    @Test
    public void failedRegistrationDoesNotWithdrawItsConcurrentReplacement() throws Exception {
        // 只拒绝携带原始资源的登记，让同 ID 替换服务可以独立完成。
        MetContainer local = new MetContainer(new AddressPool(), this.settings);
        MetPublisher publisher = local.createPublisher();
        Object originalResource = new Object();
        IllegalArgumentException rejected = new IllegalArgumentException("invalid original service");
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        local.subscribe(event -> {
            if (event.getType() != MetContextEventType.REGISTERED) {
                return;
            }
            if (event.getServiceInfo().getBindResource() != originalResource) {
                return;
            }
            // 回调挂起时让测试线程完成回收和替换，再放行原登记失败。
            checking.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            throw rejected;
        });

        // 让原登记停在事件处理中，另一线程撤销它并发布同 ID 的新服务。
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<MetBindInfo<Echo>> original = worker.submit(() -> {
                var builder = publisher.rsfService(Echo.class);
                builder.bindResource(originalResource);
                return builder.register();
            });
            assertTrue(checking.await(5, TimeUnit.SECONDS));
            MetBindInfo<Echo> old = local.getRsfBindInfo(Echo.class);
            assertTrue(local.recoverService(old.getBindID()));
            MetBindInfo<Echo> replacement = publisher.rsfService(Echo.class).register();

            // 原登记失败只能撤销自己的记录，不能按 ID 误删后来发布的服务。
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                original.get(5, TimeUnit.SECONDS);
            });
            assertSame(rejected, failure.getCause());
            MetBindInfo<Echo> surviving = local.getRsfBindInfo(Echo.class);
            assertEquals(replacement.getBindID(), surviving.getBindID());
            assertNull(surviving.getBindResource());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void eachContainerSubscriptionCanBeCancelledIndependently() {
        // 同一监听器注册两次，每次得到独立的取消句柄。
        List<MetContextEvent> events = new ArrayList<>();
        MetContextListener listener = events::add;
        Cancellable first = this.container.subscribe(listener);
        Cancellable second = this.container.subscribe(listener);

        // 同一监听对象的两次订阅彼此独立，取消其中一次不影响另一次。
        assertTrue(first.cancel());
        assertFalse(first.cancel());
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(1, events.size());

        // 两次订阅都取消后，移除服务不再产生回调。
        assertTrue(second.cancel());
        assertTrue(this.container.recoverService(service.getBindID()));
        assertEquals(1, events.size());
    }
}
