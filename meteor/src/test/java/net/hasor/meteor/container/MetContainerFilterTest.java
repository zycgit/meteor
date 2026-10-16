/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.ContainerFixtures.Echo;
import net.hasor.meteor.container.ContainerFixtures.PassFilter;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerFilterTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void serviceFilterOverridesGlobalAndRunsAfterRemainingGlobals() {
        // 服务私有过滤器覆盖同 ID 全局过滤器，同时保留其余全局过滤器。
        MetFilter global = new PassFilter();
        MetFilter overridden = new PassFilter();
        MetFilter local = new PassFilter();
        MetFilter tail = new PassFilter();
        this.publisher.bindFilter("first", overridden).bindFilter("second", global);
        var serviceBuilder = this.publisher.rsfService(Echo.class);
        serviceBuilder.bindFilter("first", local);
        serviceBuilder.bindFilter("tail", tail);
        MetBindInfo<Echo> service = serviceBuilder.register();

        // 顺序为剩余全局过滤器，再按声明顺序执行私有过滤器。
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(3, filters.length);
        assertSame(global, filters[0].get());
        assertSame(local, filters[1].get());
        assertSame(tail, filters[2].get());
    }

    @Test
    public void globalRegistrationInvalidatesCachedFilters() {
        // 先查询没有过滤器的服务，形成空缓存。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertEquals(0, this.container.getFilterProviders(service.getBindID()).length);

        // 随后增加全局过滤器，已有服务的缓存应立即失效。
        MetFilter filter = new PassFilter();
        this.publisher.bindFilter("global", filter);
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(1, filters.length);
        assertSame(filter, filters[0].get());
    }

    @Test
    public void mutatingReturnedFiltersDoesNotModifyTheCachedArray() {
        // 调用方修改拿到的数组，不能污染容器缓存。
        MetFilter filter = new PassFilter();
        this.publisher.bindFilter("global", filter);
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        filters[0] = null;

        Supplier<MetFilter>[] queried = this.container.getFilterProviders(service.getBindID());
        assertSame(filter, queried[0].get());
    }

    @Test
    public void duplicateGlobalFilterIdKeepsTheOriginalFilter() {
        // 已登记全局过滤器的 ID 不能再次使用，原实例必须保持可查询。
        MetFilter filter = new PassFilter();
        this.publisher.bindFilter("global", filter);
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertThrows(IllegalStateException.class, () -> this.publisher.bindFilter("global", new PassFilter()));
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(1, filters.length);
        assertSame(filter, filters[0].get());
    }

    @Test
    public void filterSupplierRemainsLazyUntilResolved() {
        // 登记和查询过滤器列表不应执行全局过滤器工厂。
        AtomicInteger created = new AtomicInteger();
        this.publisher.bindFilter("supplier", () -> {
            created.incrementAndGet();
            return new PassFilter();
        });
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(0, created.get());

        // 解析过滤器实例才执行一次工厂。
        assertTrue(filters[0].get() instanceof PassFilter);
        assertEquals(1, created.get());
    }

    @Test
    public void filterClassRemainsLazyUntilResolved() {
        // 用构造计数验证私有过滤器的类绑定也延迟实例化。
        CountingFilter.created.set(0);
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindFilter("class", CountingFilter.class);
        MetBindInfo<Echo> service = builder.register();
        Supplier<MetFilter>[] filters = this.container.getFilterProviders(service.getBindID());
        assertEquals(0, CountingFilter.created.get());

        // 取得实例时才调用公开构造方法。
        assertTrue(filters[0].get() instanceof CountingFilter);
        assertEquals(1, CountingFilter.created.get());
    }

    public static class CountingFilter extends PassFilter {
        private static final AtomicInteger created = new AtomicInteger();

        public CountingFilter() {
            CountingFilter.created.incrementAndGet();
        }
    }
}
