/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.*;
import net.hasor.meteor.address.AddressBucket;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerTest {
    private final AddressPool  addresses      = new AddressPool("unit", 1000);
    private final MetSettings  settings       = proxy(MetSettings.class, (p, method, args) -> {
        switch (method.getName()) {
            case "getDefaultGroup":
                return "default";
            case "getDefaultVersion":
                return "1.0";
            case "getSerializeType":
                return "Java";
            case "getDefaultTimeout":
                return 3000;
            case "getUnitName":
                return "unit";
            default:
                throw new AssertionError("Unexpected setting: " + method.getName());
        }
    });
    private final List<String> serviceChanges = new ArrayList<>();
    private final MetContainer container      = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher      = this.container.createPublisher();

    @Before
    public void observeServiceChanges() {
        this.container.subscribe(event -> this.serviceChanges.add(event.getServiceId()));
    }

    @Test
    public void registrationBecomesVisibleAfterAddressesAreReady() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AddressPool addresses = new AddressPool() {
            @Override
            public void registerBucket(AddressBucket bucket) {
                preparing.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                super.registerBucket(bucket);
            }
        };
        MetContainer local = new MetContainer(addresses, this.settings);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<MetBindInfo<Echo>> publishing = worker.submit(() -> {
                return local.createPublisher().rsfService(Echo.class).bindAddress("rsf://localhost:2181/default").register();
            });
            try {
                assertTrue(preparing.await(5, TimeUnit.SECONDS));
                assertNull(local.getRsfBindInfo(Echo.class));
                assertTrue(local.getServiceIDs().isEmpty());
            } finally {
                release.countDown();
            }
            MetBindInfo<Echo> service = publishing.get(5, TimeUnit.SECONDS);
            assertNotNull(local.getRsfBindInfo(service.getBindID()));
            assertNotNull(addresses.nextAddress(service.getBindID(), "echo", null));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void registrationPreservesAddressesDiscoveredBeforeServiceDeclaration() throws Exception {
        String id = "[default]" + Echo.class.getName() + "-1.0";
        InterAddress discovered = new InterAddress("rsf://localhost:2181/default");
        InterAddress fixed = new InterAddress("rsf://localhost:2182/default");
        this.addresses.appendAddress(id, discovered);
        String methodRoute = "<route><return value='addresses'/></route>";
        this.addresses.updateMethodRoute(id, methodRoute);

        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress(fixed).register();
        assertEquals(id, service.getBindID());
        assertEquals(Set.of(discovered, fixed), new HashSet<>(this.addresses.queryAllAddresses(id)));
        assertEquals(List.of(fixed), this.addresses.getBucket(id).getStaticAddresses());
        assertEquals(methodRoute, this.addresses.methodRoute(id));
    }

    @Test
    public void subscriptionsObserveCommittedChangesOutsideContainerLock() {
        // 独立容器不要求监听器；多个监听器失败隔离，读取的是变更完成后的数据。
        MetContainer local = new MetContainer(new AddressPool(), this.settings);
        MetBindInfo<Echo> earlier = local.createPublisher().rsfService(Echo.class).name("earlier").register();
        assertNotNull(local.getRsfBindInfo(earlier.getBindID()));
        List<MetContextEvent> events = new ArrayList<>();
        local.subscribe(event -> {
            throw new IllegalStateException("listener failure");
        });
        local.subscribe(event -> {
            assertFalse(Thread.holdsLock(local));
            if (event.getType() == MetContextEventType.REGISTERED) {
                assertSame(local.getRsfBindInfo(event.getServiceId()), event.getServiceInfo());
            } else {
                assertNull(local.getRsfBindInfo(event.getServiceId()));
            }
            events.add(event);
        });

        // 提供者发布和移除各通知一次，失败及重复操作不产生事件。
        MetPublisher publisher = local.createPublisher();
        MetBindInfo<Echo> service = publisher.rsfService(Echo.class, value -> value).register();
        assertThrows(IllegalStateException.class, () -> publisher.rsfService(Echo.class).register());
        assertTrue(local.recoverService(service.getBindID()));
        assertFalse(local.recoverService(service.getBindID()));
        assertEquals(2, events.size());
        assertEquals(MetContextEventType.REGISTERED, events.get(0).getType());
        assertEquals(MetContextEventType.REMOVED, events.get(1).getType());
        assertEquals(MetServiceType.Provider, events.get(0).getServiceInfo().getServiceType());
        assertSame(events.get(0).getServiceInfo(), events.get(1).getServiceInfo());

        // 同一服务 ID 重新声明为消费者，复用事件类型；旧事件仍指向原提供者。
        MetBindInfo<Echo> consumer = publisher.rsfService(Echo.class).register();
        assertEquals(service.getBindID(), consumer.getBindID());
        assertThrows(IllegalStateException.class, () -> publisher.rsfService(Echo.class).register());
        assertTrue(local.recoverService(consumer.getBindID()));
        assertFalse(local.recoverService(consumer.getBindID()));
        assertEquals(4, events.size());
        assertEquals(MetContextEventType.REGISTERED, events.get(2).getType());
        assertEquals(MetContextEventType.REMOVED, events.get(3).getType());
        assertEquals(MetServiceType.Consumer, events.get(2).getServiceInfo().getServiceType());
        assertSame(events.get(2).getServiceInfo(), events.get(3).getServiceInfo());
        assertEquals(MetServiceType.Provider, events.get(1).getServiceInfo().getServiceType());
        for (MetContextEvent event : events) {
            assertEquals(service.getBindID(), event.getServiceId());
        }
    }

    @Test
    public void eachContainerSubscriptionCanBeCancelledIndependently() {
        List<MetContextEvent> events = new ArrayList<>();
        MetContextListener listener = events::add;
        Cancellable first = this.container.subscribe(listener);
        Cancellable second = this.container.subscribe(listener);

        // 同一监听对象的两次订阅彼此独立，取消其中一次不影响另一次。
        assertTrue(first.cancel());
        assertFalse(first.cancel());
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(1, events.size());
        assertTrue(second.cancel());
        assertTrue(this.container.recoverService(service.getBindID()));
        assertEquals(1, events.size());
    }

    @Test
    public void registersAndFindsConsumerWithoutRuntimeContext() {
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertSame(this.settings, this.publisher.getSettings());
        assertEquals("default", service.getBindGroup());
        assertEquals(Echo.class.getName(), service.getBindName());
        assertEquals("1.0", service.getBindVersion());
        assertNull(service.getSerializeType());
        assertEquals(3000, service.getClientTimeout());
        assertEquals(MetServiceType.Consumer, service.getServiceType());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(Echo.class).getBindID());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo("default", Echo.class.getName(), "1.0").getBindID());
        assertEquals(Collections.singletonList(service.getBindID()), this.container.getServiceIDs());
        assertEquals(List.of(service.getBindID()), this.serviceChanges);
        assertNull(this.container.getProvider(service));
        service.setMetaData("tag", "value");
        assertEquals("value", this.container.getRsfBindInfo(service.getBindID()).getMetaData("tag"));
    }

    @Test
    public void annotationsSupplyRegistrationAndLookupDefaults() {
        MetBindInfo<AnnotatedEcho> service = this.publisher.rsfService(AnnotatedEcho.class).register();
        assertEquals("[group]echo-2", service.getBindID());
        assertEquals("Json", service.getSerializeType());
        assertEquals(1200, service.getClientTimeout());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(AnnotatedEcho.class).getBindID());
    }

    @Test
    public void builderOverridesDefaultsAndRetainsServiceOptions() {
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).group("custom").name("echo").version("3").timeout(2500).serialize("Hessian").protocol("RSF/1.0", "Http/Hprose").aliasName("http", "echo").asAloneThreadPool().asMessage().asShadow().register();
        assertEquals("[custom]echo-3", service.getBindID());
        assertEquals(2500, service.getClientTimeout());
        assertEquals("Hessian", service.getSerializeType());
        assertEquals(new HashSet<>(Arrays.asList("RSF/1.0", "Http/Hprose")), service.getBindProtocols());
        assertTrue(service.isMessage());
        assertTrue(service.isShadow());
        assertFalse(service.isSharedThreadPool());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo("http", "echo").getBindID());
        assertEquals(Collections.singletonList("echo"), this.container.getServiceIDs("http"));
    }

    @Test
    public void supportsInstanceSupplierAndClassProvidersLazily() {
        Echo instance = new EchoImpl();
        AtomicInteger created = new AtomicInteger();
        MetBindInfo<Echo> first = this.publisher.rsfService(Echo.class, instance).name("instance").register();
        Supplier<Echo> supplier = () -> {
            created.incrementAndGet();
            return new EchoImpl();
        };
        MetBindInfo<Echo> second = this.publisher.rsfService(Echo.class, supplier).name("supplier").register();
        MetBindInfo<Echo> third = this.publisher.rsfService(Echo.class, EchoImpl.class).name("class").register();
        assertEquals(0, created.get());
        assertSame(instance, this.container.getProvider(first).get());
        assertEquals("hello", this.container.getProvider(second).get().echo("hello"));
        assertEquals(1, created.get());
        assertTrue(this.container.getProvider(third).get() instanceof EchoImpl);
        assertEquals(MetServiceType.Provider, third.getServiceType());
    }

    @Test
    public void classProviderReportsMissingPublicConstructorWhenResolved() {
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, PrivateEcho.class).register();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.container.getProvider(service).get());
        assertTrue(failure.getMessage().contains("register an instance or Supplier"));
    }

    @Test
    public void rejectsMissingProviderWithoutPublishingPartialService() {
        MetException failure = assertThrows(MetException.class, () -> this.publisher.rsfService(Echo.class).toProvider(null).aliasName("http", "echo").register());
        assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
        assertTrue(this.container.getServiceIDs().isEmpty());
        assertNull(this.container.getRsfBindInfo("http", "echo"));
        assertTrue(this.serviceChanges.isEmpty());
        assertNotNull(this.publisher.rsfService(Echo.class, new EchoImpl()).register());
    }

    @Test
    public void duplicateServiceDoesNotReplaceOriginalProvider() {
        Echo original = new EchoImpl();
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, original).register();
        assertThrows(IllegalStateException.class, () -> this.publisher.rsfService(Echo.class).register());
        assertSame(original, this.container.getProvider(service).get());
        assertEquals(1, this.container.getServiceIDs().size());
        assertEquals(List.of(service.getBindID()), this.serviceChanges);
    }

    @Test
    public void concurrentDuplicateRegistrationPublishesOnlyOneService() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        this.container.createPublisher().rsfService(Echo.class).register();
                        return true;
                    } catch (IllegalStateException duplicate) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int registered = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    registered++;
                }
            }
            assertEquals(1, registered);
            assertEquals(1, this.container.getServiceIDs().size());
            assertEquals(this.container.getServiceIDs(), this.serviceChanges);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void serviceFilterOverridesGlobalAndRunsAfterRemainingGlobals() {
        MetFilter global = new PassFilter();
        MetFilter overridden = new PassFilter();
        MetFilter local = new PassFilter();
        MetFilter tail = new PassFilter();
        this.publisher.bindFilter("first", overridden).bindFilter("second", global);
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindFilter("first", local).bindFilter("tail", tail).register();
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(3, filters.length);
        assertSame(global, filters[0].get());
        assertSame(local, filters[1].get());
        assertSame(tail, filters[2].get());
    }

    @Test
    public void globalRegistrationInvalidatesCachedFiltersAndRejectsDuplicateIds() {
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(0, this.container.getFilterProviders(service.getBindID()).length);
        MetFilter filter = new PassFilter();
        this.publisher.bindFilter("global", filter);
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertSame(filter, filters[0].get());
        filters[0] = null;
        assertSame(filter, this.container.getFilterProviders(service.getBindID())[0].get());
        assertThrows(IllegalStateException.class, () -> this.publisher.bindFilter("global", new PassFilter()));
        assertEquals(1, this.container.getFilterProviders(service.getBindID()).length);
    }

    @Test
    public void filterSuppliersAndClassesRemainLazy() {
        AtomicInteger created = new AtomicInteger();
        this.publisher.bindFilter("supplier", () -> {
            created.incrementAndGet();
            return new PassFilter();
        });
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindFilter("class", PassFilter.class).register();
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(0, created.get());
        assertTrue(filters[0].get() instanceof PassFilter);
        assertEquals(1, created.get());
        assertTrue(filters[1].get() instanceof PassFilter);
    }

    @Test
    public void registersAliasesForProvidersAndConsumers() {
        MetBindInfo<Echo> provider = this.publisher.rsfService(Echo.class, new EchoImpl()).aliasName("http", "provider").register();
        MetBindInfo<Echo> consumer = this.publisher.rsfService(Echo.class).name("consumer").aliasName("http", "consumer").register();
        assertEquals(provider.getBindID(), this.container.getRsfBindInfo("http", "provider").getBindID());
        assertEquals(consumer.getBindID(), this.container.getRsfBindInfo("http", "consumer").getBindID());
        assertEquals(new HashSet<>(Arrays.asList("provider", "consumer")), new HashSet<>(this.container.getServiceIDs("http")));
    }

    @Test
    public void recoveryRemovesAliasesProviderAddressesAndCachedPrivateFilters() throws Exception {
        InterAddress oldAddress = new InterAddress("rsf://127.0.0.1:2101/unit");
        MetFilter oldFilter = new PassFilter();
        MetBindInfo<Echo> first = this.publisher.rsfService(Echo.class, new EchoImpl()).aliasName("http", "old").bindFilter("local", oldFilter).bindAddress(oldAddress).register();
        assertEquals(Collections.singletonList(oldAddress), this.addresses.queryAllAddresses(first.getBindID()));
        assertSame(oldFilter, this.container.getFilterProviders(first.getBindID())[0].get());
        assertTrue(this.container.recoverService(first.getBindID()));
        assertFalse(this.container.recoverService(first.getBindID()));
        // 发布和成功回收各通知一次，重复回收不产生额外事件。
        assertEquals(List.of(first.getBindID(), first.getBindID()), this.serviceChanges);
        assertNull(this.container.getRsfBindInfo(first.getBindID()));
        assertNull(this.container.getRsfBindInfo("http", "old"));
        assertNull(this.container.getProvider(first));
        assertNull(this.addresses.getBucket(first.getBindID()));
        assertNull(this.addresses.nextAddress(first.getBindID(), "echo", new Object[0]));
        assertTrue(this.container.getServiceIDs("http").isEmpty());
        assertEquals(0, this.container.getFilterProviders(first.getBindID()).length);
        MetFilter newFilter = new PassFilter();
        MetBindInfo<Echo> second = this.publisher.rsfService(Echo.class).bindFilter("local", newFilter).register();
        assertSame(newFilter, this.container.getFilterProviders(second.getBindID())[0].get());
        assertTrue(this.addresses.queryAllAddresses(second.getBindID()).isEmpty());
    }

    @Test
    public void missingServiceAndAliasesHaveNoProviderOrFilters() {
        assertNull(this.container.getRsfBindInfo("absent"));
        assertNull(this.container.getRsfBindInfo("absent", "absent"));
        assertTrue(this.container.getServiceIDs("absent").isEmpty());
        assertEquals(0, this.container.getFilterProviders("absent").length);
    }

    @Test
    public void uriAddressListRegistersAllAddresses() throws Exception {
        URI first = new URI("rsf://127.0.0.1:2101/unit");
        URI second = new URI("rsf://127.0.0.1:2102/unit");
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress(first, second).register();
        assertEquals(new HashSet<>(Arrays.asList(new InterAddress(first), new InterAddress(second))), new HashSet<>(this.addresses.queryAllAddresses(service.getBindID())));
    }

    @Test
    public void hostAndStringAddressRegistrationUseProvidedPool() throws Exception {
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress("127.0.0.1", 2101).bindAddress("rsf://127.0.0.1:2102/unit", "rsf://127.0.0.1:2103/unit").register();
        List<InterAddress> bound = this.addresses.queryAllAddresses(service.getBindID());
        assertEquals(3, bound.size());
        assertTrue(bound.contains(new InterAddress("rsf://127.0.0.1:2101/unit")));
    }

    @Test
    public void recoveringAnAliasCompetitorDoesNotRemoveItsOriginalOwner() {
        MetBindInfo<Echo> first = this.publisher.rsfService(Echo.class).name("first").aliasName("http", "shared").register();
        MetBindInfo<Echo> second = this.publisher.rsfService(Echo.class).name("second").aliasName("http", "shared").register();
        assertEquals(first.getBindID(), this.container.getRsfBindInfo("http", "shared").getBindID());
        assertTrue(this.container.recoverService(second.getBindID()));
        assertEquals(first.getBindID(), this.container.getRsfBindInfo("http", "shared").getBindID());
        assertTrue(this.container.recoverService(first.getBindID()));
        assertNull(this.container.getRsfBindInfo("http", "shared"));
        MetBindInfo<Echo> replacement = this.publisher.rsfService(Echo.class).aliasName("http", "shared").register();
        assertEquals(replacement.getBindID(), this.container.getRsfBindInfo("http", "shared").getBindID());
    }

    @Test
    public void publishedServiceRoutesReachTheAddressPoolBeforeSelection() {
        InterAddress first = new InterAddress("rsf", "127.0.0.1", 2101, "unit");
        InterAddress second = new InterAddress("rsf", "127.0.0.1", 2102, "unit");
        String route = "<route><filter name='selected' from='addresses' item='a' test='a.port == 2102'/><return value='selected'/></route>";
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress(first, second).updateServiceRoute(route).register();
        assertEquals(route, this.addresses.serviceRoute(service.getBindID()));
        assertEquals(second, this.addresses.nextAddress(service.getBindID(), "echo", new Object[0]));
        assertTrue(this.container.recoverService(service.getBindID()));
        MetBindInfo<Echo> replacement = this.publisher.rsfService(Echo.class).bindAddress(first).register();
        assertNull(this.addresses.serviceRoute(replacement.getBindID()));
        assertEquals(first, this.addresses.nextAddress(replacement.getBindID(), "echo", new Object[0]));
    }

    @Test
    public void invalidBuilderValuesLeaveNoPublishedServiceAndCanBeCorrected() {
        MetPublisher.ConfigurationBuilder<Echo> builder = this.publisher.rsfService(Echo.class);
        for (Runnable invalid : Arrays.<Runnable>asList(() -> builder.group("bad/group"), () -> builder.name("bad/name"), () -> builder.version("bad/version"), () -> builder.serialize("bad/type"), () -> builder.timeout(0), () -> builder.timeout(-1))) {
            assertThrows(IllegalStateException.class, invalid);
            assertTrue(this.container.getServiceIDs().isEmpty());
        }
        // 错误规则在装配时拒绝，不能发布一个静默忽略治理配置的服务。
        assertThrows(IllegalArgumentException.class, () -> builder.updateServiceRoute("<route/>"));
        assertThrows(IllegalArgumentException.class, () -> builder.updateFlowControl("<invalid/>"));
        assertTrue(this.container.getServiceIDs().isEmpty());
        MetBindInfo<Echo> valid = builder.group("fixed").name("echo").version("1").serialize("Java").timeout(1000).register();
        assertEquals("[fixed]echo-1", valid.getBindID());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static <T extends Throwable> T assertThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            assertTrue("Unexpected failure: " + failure, type.isInstance(failure));
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    public interface Echo {
        String echo(String value);
    }

    @MetService(group = "group", name = "echo", version = "2", serializeType = "Json", clientTimeout = 1200)
    public interface AnnotatedEcho extends Echo {
    }

    public static class EchoImpl implements Echo {
        @Override
        public String echo(String value) {
            return value;
        }
    }

    public static class PrivateEcho extends EchoImpl {
        private PrivateEcho() {
        }
    }

    public static class PassFilter implements MetFilter {
        @Override
        public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
            chain.doFilter(request, response);
        }
    }
}
