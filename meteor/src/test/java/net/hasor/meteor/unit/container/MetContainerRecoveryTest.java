/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.container;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.unit.container.ContainerFixtures.Echo;
import net.hasor.meteor.unit.container.ContainerFixtures.EchoImpl;
import net.hasor.meteor.unit.container.ContainerFixtures.PassFilter;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerRecoveryTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void providerAliasResolvesTheRegisteredProvider() {
        // 提供者可通过配置的别名查到同一服务。
        var builder = this.publisher.rsfService(Echo.class, new EchoImpl());
        builder.aliasName("http", "provider");
        MetBindInfo<Echo> service = builder.register();
        MetBindInfo<?> aliased = this.container.getRsfBindInfo("http", "provider");
        assertEquals(service.getBindID(), aliased.getBindID());
    }

    @Test
    public void consumerAliasResolvesTheRegisteredConsumer() {
        // 消费者可通过配置的别名查到同一服务。
        var builder = this.publisher.rsfService(Echo.class);
        builder.name("consumer");
        builder.aliasName("http", "consumer");
        MetBindInfo<Echo> service = builder.register();
        MetBindInfo<?> aliased = this.container.getRsfBindInfo("http", "consumer");
        assertEquals(service.getBindID(), aliased.getBindID());
    }

    @Test
    public void aliasCategoryListsBothProviderAndConsumerNames() {
        // 一个别名类别可以收录提供者与消费者的不同名称。
        var providerBuilder = this.publisher.rsfService(Echo.class, new EchoImpl());
        providerBuilder.aliasName("http", "provider");
        providerBuilder.register();
        var consumerBuilder = this.publisher.rsfService(Echo.class);
        consumerBuilder.name("consumer");
        consumerBuilder.aliasName("http", "consumer");
        consumerBuilder.register();

        // 类别查询返回完整别名集合，不依赖服务角色。
        Set<String> aliases = new HashSet<>(this.container.getServiceIDs("http"));
        assertEquals(Set.of("provider", "consumer"), aliases);
    }

    @Test
    public void recoveryRemovesAliasesProviderAddressesAndCachedPrivateFilters() throws Exception {
        // 发布带别名、静态地址和私有过滤器的提供者，并预热过滤器缓存。
        List<String> serviceChanges = new ArrayList<>();
        this.container.subscribe(event -> serviceChanges.add(event.getServiceId()));
        InterAddress oldAddress = new InterAddress("rsf://127.0.0.1:2101/unit");
        MetFilter oldFilter = new PassFilter();
        var firstBuilder = this.publisher.rsfService(Echo.class, new EchoImpl());
        firstBuilder.aliasName("http", "old");
        firstBuilder.bindFilter("local", oldFilter);
        firstBuilder.bindAddress(oldAddress);
        MetBindInfo<Echo> first = firstBuilder.register();
        List<InterAddress> oldAddresses = this.addresses.queryAllAddresses(first.getBindID());
        assertEquals(List.of(oldAddress), oldAddresses);
        Supplier<MetFilter>[] oldFilters = this.container.getFilterProviders(first.getBindID());
        assertSame(oldFilter, oldFilters[0].get());

        // 一次成功回收清理所有关联资源，重复回收不发送额外事件。
        assertTrue(this.container.recoverService(first.getBindID()));
        assertFalse(this.container.recoverService(first.getBindID()));
        assertEquals(List.of(first.getBindID(), first.getBindID()), serviceChanges);
        assertNull(this.container.getRsfBindInfo(first.getBindID()));
        assertNull(this.container.getRsfBindInfo("http", "old"));
        assertNull(this.container.getProvider(first));
        assertNull(this.addresses.getBucket(first.getBindID()));
        assertNull(this.addresses.nextAddress(first.getBindID(), "echo", new Object[0]));
        assertTrue(this.container.getServiceIDs("http").isEmpty());
        assertEquals(0, this.container.getFilterProviders(first.getBindID()).length);

        // 同 ID 新服务不能继承回收前的私有过滤器缓存或静态地址。
        MetFilter newFilter = new PassFilter();
        var secondBuilder = this.publisher.rsfService(Echo.class);
        secondBuilder.bindFilter("local", newFilter);
        MetBindInfo<Echo> second = secondBuilder.register();
        Supplier<MetFilter>[] newFilters = this.container.getFilterProviders(second.getBindID());
        assertSame(newFilter, newFilters[0].get());
        assertTrue(this.addresses.queryAllAddresses(second.getBindID()).isEmpty());
    }

    @Test
    public void recoveringAnAliasCompetitorDoesNotRemoveItsOriginalOwner() {
        // 两个不同服务争用一个别名，先登记者保持所有权。
        var firstBuilder = this.publisher.rsfService(Echo.class);
        firstBuilder.name("first");
        firstBuilder.aliasName("http", "shared");
        MetBindInfo<Echo> first = firstBuilder.register();
        var secondBuilder = this.publisher.rsfService(Echo.class);
        secondBuilder.name("second");
        secondBuilder.aliasName("http", "shared");
        MetBindInfo<Echo> second = secondBuilder.register();
        MetBindInfo<?> beforeRecovery = this.container.getRsfBindInfo("http", "shared");
        assertEquals(first.getBindID(), beforeRecovery.getBindID());

        // 回收竞争失败的服务不会删除原所有者的别名。
        assertTrue(this.container.recoverService(second.getBindID()));
        assertNull(this.container.getRsfBindInfo(second.getBindID()));
        MetBindInfo<?> afterRecovery = this.container.getRsfBindInfo("http", "shared");
        assertEquals(first.getBindID(), afterRecovery.getBindID());
    }

    @Test
    public void recoveredAliasOwnerReleasesTheNameForANewService() {
        // 当前所有者回收后，别名查询也随之清空。
        var originalBuilder = this.publisher.rsfService(Echo.class);
        originalBuilder.name("original");
        originalBuilder.aliasName("http", "shared");
        MetBindInfo<Echo> original = originalBuilder.register();
        assertTrue(this.container.recoverService(original.getBindID()));
        assertNull(this.container.getRsfBindInfo("http", "shared"));

        // 不同服务随后可以取得已释放别名。
        var replacementBuilder = this.publisher.rsfService(Echo.class);
        replacementBuilder.aliasName("http", "shared");
        MetBindInfo<Echo> replacement = replacementBuilder.register();
        MetBindInfo<?> aliased = this.container.getRsfBindInfo("http", "shared");
        assertEquals(replacement.getBindID(), aliased.getBindID());
    }
}
