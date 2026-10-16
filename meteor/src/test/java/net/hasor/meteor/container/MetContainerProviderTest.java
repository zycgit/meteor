/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.ContainerFixtures.Echo;
import net.hasor.meteor.container.ContainerFixtures.EchoImpl;
import net.hasor.meteor.container.ContainerFixtures.PrivateEcho;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerProviderTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void instanceProviderReturnsTheRegisteredInstance() {
        // 实例绑定直接复用调用方交给容器的对象。
        Echo instance = new EchoImpl();
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, instance).register();
        assertSame(instance, this.container.getProvider(service).get());
        assertEquals(MetServiceType.Provider, service.getServiceType());
    }

    @Test
    public void supplierProviderIsCreatedOnlyWhenResolved() {
        // 提供者工厂在登记和取得 Supplier 时都不执行。
        AtomicInteger created = new AtomicInteger();
        Supplier<Echo> supplier = () -> {
            created.incrementAndGet();
            return new EchoImpl();
        };
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, supplier).register();
        Supplier<Echo> registered = this.container.getProvider(service);
        assertEquals(0, created.get());

        // 解析一次提供者才创建实例，并且实例可执行业务方法。
        Echo instance = registered.get();
        assertEquals("hello", instance.echo("hello"));
        assertEquals(1, created.get());
    }

    @Test
    public void classProviderIsCreatedOnlyWhenResolved() {
        // 使用当前测试专属的实现类计数，不需要读取容器私有状态。
        CountingEcho.created.set(0);
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, CountingEcho.class).register();
        Supplier<Echo> provider = this.container.getProvider(service);
        assertEquals(0, CountingEcho.created.get());

        // 解析提供者时才调用公开构造方法。
        assertTrue(provider.get() instanceof CountingEcho);
        assertEquals(1, CountingEcho.created.get());
        assertEquals(MetServiceType.Provider, service.getServiceType());
    }

    @Test
    public void classProviderReportsMissingPublicConstructorWhenResolved() {
        // 登记允许延迟构造，缺少公开构造方法的错误在解析提供者时抛出。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, PrivateEcho.class).register();
        Supplier<Echo> provider = this.container.getProvider(service);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, provider::get);
        assertTrue(failure.getMessage().contains("register an instance or Supplier"));
    }

    @Test
    public void rejectsMissingProviderWithoutPublishingPartialService() {
        // 缺少提供者的服务带有别名，失败时不能留下部分登记。
        List<String> serviceChanges = new ArrayList<>();
        this.container.subscribe(event -> serviceChanges.add(event.getServiceId()));
        var invalid = this.publisher.rsfService(Echo.class).toProvider(null);
        invalid.aliasName("http", "echo");
        MetException failure = assertThrows(MetException.class, invalid::register);
        assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
        assertTrue(this.container.getServiceIDs().isEmpty());
        assertNull(this.container.getRsfBindInfo("http", "echo"));
        assertTrue(serviceChanges.isEmpty());

        // 一次失败不污染同 ID 服务的后续有效发布。
        assertNotNull(this.publisher.rsfService(Echo.class, new EchoImpl()).register());
    }

    public static class CountingEcho extends EchoImpl {
        private static final AtomicInteger created = new AtomicInteger();

        public CountingEcho() {
            CountingEcho.created.incrementAndGet();
        }
    }
}
