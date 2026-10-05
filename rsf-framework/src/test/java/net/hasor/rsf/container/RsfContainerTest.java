/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.container;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.rsf.*;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.RsfServiceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class RsfContainerTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final RsfSettings  settings  = proxy(RsfSettings.class, (p, method, args) -> {
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
    private final RsfContainer container = new RsfContainer(this.addresses, this.settings);
    private final RsfPublisher publisher = this.container.createPublisher();

    @Test
    public void registersAndFindsConsumerWithoutRuntimeContext() {
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertSame(this.settings, this.publisher.getSettings());
        assertEquals("default", service.getBindGroup());
        assertEquals(Echo.class.getName(), service.getBindName());
        assertEquals("1.0", service.getBindVersion());
        assertEquals("Java", service.getSerializeType());
        assertEquals(3000, service.getClientTimeout());
        assertEquals(RsfServiceType.Consumer, service.getServiceType());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(Echo.class).getBindID());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo("default", Echo.class.getName(), "1.0").getBindID());
        assertEquals(Collections.singletonList(service.getBindID()), this.container.getServiceIDs());
        assertNull(this.container.getProvider(service));
        service.setMetaData("tag", "value");
        assertEquals("value", this.container.getRsfBindInfo(service.getBindID()).getMetaData("tag"));
    }

    @Test
    public void annotationsSupplyRegistrationAndLookupDefaults() {
        RsfBindInfo<AnnotatedEcho> service = this.publisher.rsfService(AnnotatedEcho.class).register();
        assertEquals("[group]echo-2", service.getBindID());
        assertEquals("Json", service.getSerializeType());
        assertEquals(1200, service.getClientTimeout());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(AnnotatedEcho.class).getBindID());
    }

    @Test
    public void builderOverridesDefaultsAndRetainsServiceOptions() {
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).group("custom").name("echo").version("3").timeout(2500).serialize("Hessian").protocol("RSF/1.0", "Http/Hprose").aliasName("http", "echo").asAloneThreadPool().asMessage().asShadow().register();
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
        RsfBindInfo<Echo> first = this.publisher.rsfService(Echo.class, instance).name("instance").register();
        Supplier<Echo> supplier = () -> {
            created.incrementAndGet();
            return new EchoImpl();
        };
        RsfBindInfo<Echo> second = this.publisher.rsfService(Echo.class, supplier).name("supplier").register();
        RsfBindInfo<Echo> third = this.publisher.rsfService(Echo.class, EchoImpl.class).name("class").register();
        assertEquals(0, created.get());
        assertSame(instance, this.container.getProvider(first).get());
        assertEquals("hello", this.container.getProvider(second).get().echo("hello"));
        assertEquals(1, created.get());
        assertTrue(this.container.getProvider(third).get() instanceof EchoImpl);
        assertEquals(RsfServiceType.Provider, third.getServiceType());
    }

    @Test
    public void classProviderReportsMissingPublicConstructorWhenResolved() {
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class, PrivateEcho.class).register();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.container.getProvider(service).get());
        assertTrue(failure.getMessage().contains("register an instance or Supplier"));
    }

    @Test
    public void rejectsMissingProviderWithoutPublishingPartialService() {
        RsfException failure = assertThrows(RsfException.class, () -> this.publisher.rsfService(Echo.class).toProvider(null).aliasName("http", "echo").register());
        assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
        assertTrue(this.container.getServiceIDs().isEmpty());
        assertNull(this.container.getRsfBindInfo("http", "echo"));
        assertNotNull(this.publisher.rsfService(Echo.class, new EchoImpl()).register());
    }

    @Test
    public void duplicateServiceDoesNotReplaceOriginalProvider() {
        Echo original = new EchoImpl();
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class, original).register();
        assertThrows(IllegalStateException.class, () -> this.publisher.rsfService(Echo.class).register());
        assertSame(original, this.container.getProvider(service).get());
        assertEquals(1, this.container.getServiceIDs().size());
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
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void serviceFilterOverridesGlobalAndRunsAfterRemainingGlobals() {
        RsfFilter global = new PassFilter();
        RsfFilter overridden = new PassFilter();
        RsfFilter local = new PassFilter();
        RsfFilter tail = new PassFilter();
        this.publisher.bindFilter("first", overridden).bindFilter("second", global);
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindFilter("first", local).bindFilter("tail", tail).register();
        Supplier<RsfFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(3, filters.length);
        assertSame(global, filters[0].get());
        assertSame(local, filters[1].get());
        assertSame(tail, filters[2].get());
    }

    @Test
    public void globalRegistrationInvalidatesCachedFiltersAndRejectsDuplicateIds() {
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(0, this.container.getFilterProviders(service.getBindID()).length);
        RsfFilter filter = new PassFilter();
        this.publisher.bindFilter("global", filter);
        Supplier<RsfFilter>[] filters = this.container.getFilterProviders(service.getBindID());
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
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindFilter("class", PassFilter.class).register();
        Supplier<RsfFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(0, created.get());
        assertTrue(filters[0].get() instanceof PassFilter);
        assertEquals(1, created.get());
        assertTrue(filters[1].get() instanceof PassFilter);
    }

    @Test
    public void registersAliasesForProvidersAndConsumers() {
        RsfBindInfo<Echo> provider = this.publisher.rsfService(Echo.class, new EchoImpl()).aliasName("http", "provider").register();
        RsfBindInfo<Echo> consumer = this.publisher.rsfService(Echo.class).name("consumer").aliasName("http", "consumer").register();
        assertEquals(provider.getBindID(), this.container.getRsfBindInfo("http", "provider").getBindID());
        assertEquals(consumer.getBindID(), this.container.getRsfBindInfo("http", "consumer").getBindID());
        assertEquals(new HashSet<>(Arrays.asList("provider", "consumer")), new HashSet<>(this.container.getServiceIDs("http")));
    }

    @Test
    public void recoveryRemovesAliasesProviderAddressesAndCachedPrivateFilters() throws Exception {
        InterAddress oldAddress = new InterAddress("rsf://127.0.0.1:2101/unit");
        RsfFilter oldFilter = new PassFilter();
        RsfBindInfo<Echo> first = this.publisher.rsfService(Echo.class, new EchoImpl()).aliasName("http", "old").bindFilter("local", oldFilter).bindAddress(oldAddress).register();
        assertEquals(Collections.singletonList(oldAddress), this.addresses.queryAllAddresses(first.getBindID()));
        assertSame(oldFilter, this.container.getFilterProviders(first.getBindID())[0].get());
        assertTrue(this.container.recoverService(first.getBindID()));
        assertFalse(this.container.recoverService(first.getBindID()));
        assertNull(this.container.getRsfBindInfo(first.getBindID()));
        assertNull(this.container.getRsfBindInfo("http", "old"));
        assertNull(this.container.getProvider(first));
        assertNull(this.addresses.getBucket(first.getBindID()));
        assertNull(this.addresses.nextAddress(first.getBindID(), "echo", new Object[0]));
        assertTrue(this.container.getServiceIDs("http").isEmpty());
        assertEquals(0, this.container.getFilterProviders(first.getBindID()).length);
        RsfFilter newFilter = new PassFilter();
        RsfBindInfo<Echo> second = this.publisher.rsfService(Echo.class).bindFilter("local", newFilter).register();
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
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress(first, second).register();
        assertEquals(new HashSet<>(Arrays.asList(new InterAddress(first), new InterAddress(second))), new HashSet<>(this.addresses.queryAllAddresses(service.getBindID())));
    }

    @Test
    public void hostAndStringAddressRegistrationUseProvidedPool() throws Exception {
        RsfBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress("127.0.0.1", 2101).bindAddress("rsf://127.0.0.1:2102/unit", "rsf://127.0.0.1:2103/unit").register();
        List<InterAddress> bound = this.addresses.queryAllAddresses(service.getBindID());
        assertEquals(3, bound.size());
        assertTrue(bound.contains(new InterAddress("rsf://127.0.0.1:2101/unit")));
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

    @RsfService(group = "group", name = "echo", version = "2", serializeType = "Json", clientTimeout = 1200)
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

    public static class PassFilter implements RsfFilter {
        @Override
        public void doFilter(RsfRequest request, RsfResponse response, RsfFilterChain chain) throws Throwable {
            chain.doFilter(request, response);
        }
    }
}
