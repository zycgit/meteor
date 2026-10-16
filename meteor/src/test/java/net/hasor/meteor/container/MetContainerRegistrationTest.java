/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.ContainerFixtures.AnnotatedEcho;
import net.hasor.meteor.container.ContainerFixtures.Echo;
import net.hasor.meteor.container.ContainerFixtures.EchoImpl;
import net.hasor.meteor.domain.MetServiceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerRegistrationTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void registersAndFindsConsumerWithoutRuntimeContext() {
        // 没有运行时上下文也能按默认配置声明消费者。
        List<String> serviceChanges = new ArrayList<>();
        this.container.subscribe(event -> serviceChanges.add(event.getServiceId()));
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        assertSame(this.settings, this.publisher.getSettings());
        assertEquals("default", service.getBindGroup());
        assertEquals(Echo.class.getName(), service.getBindName());
        assertEquals("1.0", service.getBindVersion());
        assertNull(service.getSerializeType());
        assertEquals(3000, service.getClientTimeout());
        assertEquals(MetServiceType.Consumer, service.getServiceType());
        assertFalse(service.isDistributedCall());

        // 类型、坐标和 ID 列表都能找到同一消费者。
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(Echo.class).getBindID());
        MetBindInfo<?> byIdentity = this.container.getRsfBindInfo("default", Echo.class.getName(), "1.0");
        assertEquals(service.getBindID(), byIdentity.getBindID());
        assertEquals(Collections.singletonList(service.getBindID()), this.container.getServiceIDs());
        assertEquals(List.of(service.getBindID()), serviceChanges);
        assertNull(this.container.getProvider(service));
    }

    @Test
    public void metadataUpdatesRemainVisibleThroughContainerLookup() {
        // 服务句柄更新的元数据可以从容器查询结果读到。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).register();
        service.setMetaData("tag", "value");
        MetBindInfo<?> queried = this.container.getRsfBindInfo(service.getBindID());
        assertEquals("value", queried.getMetaData("tag"));
    }

    @Test
    public void annotationsSupplyRegistrationAndLookupDefaults() {
        // 注解同时提供登记默认值与按接口查询的坐标。
        MetBindInfo<AnnotatedEcho> service = this.publisher.rsfService(AnnotatedEcho.class).register();
        assertEquals("[group]echo-2", service.getBindID());
        assertEquals("Json", service.getSerializeType());
        assertEquals(1200, service.getClientTimeout());
        assertEquals(service.getBindID(), this.container.getRsfBindInfo(AnnotatedEcho.class).getBindID());
    }

    @Test
    public void builderOverridesIdentityTimeoutAndSerializationDefaults() {
        // 显式设置坐标、超时和序列化方式，覆盖配置默认值。
        var builder = this.publisher.rsfService(Echo.class);
        builder.group("custom");
        builder.name("echo");
        builder.version("3");
        builder.timeout(2500);
        builder.serialize("Java");
        MetBindInfo<Echo> service = builder.register();
        assertEquals("[custom]echo-3", service.getBindID());
        assertEquals(2500, service.getClientTimeout());
        assertEquals("Java", service.getSerializeType());
    }

    @Test
    public void builderRetainsMessageMode() {
        // 消息模式在发布后保持生效。
        var builder = this.publisher.rsfService(Echo.class);
        builder.asMessage();
        MetBindInfo<Echo> service = builder.register();
        assertTrue(service.isMessage());
    }

    @Test
    public void builderRetainsDistributedMode() {
        // 强制分布式模式在发布后关闭本地优先。
        var builder = this.publisher.rsfService(Echo.class);
        builder.asDistributedCall();
        MetBindInfo<Echo> service = builder.register();
        assertTrue(service.isDistributedCall());
    }

    @Test
    public void builderRetainsShadowMode() {
        // 影子模式在发布后保持生效。
        var builder = this.publisher.rsfService(Echo.class);
        builder.asShadow();
        MetBindInfo<Echo> service = builder.register();
        assertTrue(service.isShadow());
    }

    @Test
    public void builderRetainsPrivateThreadPool() {
        // 独立线程池选择在发布后保持生效。
        var builder = this.publisher.rsfService(Echo.class);
        builder.asAloneThreadPool();
        MetBindInfo<Echo> service = builder.register();
        assertFalse(service.isSharedThreadPool());
    }

    @Test
    public void builderAliasResolvesTheOverriddenServiceIdentity() {
        // 自定义服务坐标通过别名查询时仍指向同一服务。
        var builder = this.publisher.rsfService(Echo.class);
        builder.group("custom");
        builder.name("echo");
        builder.version("3");
        builder.aliasName("http", "echo");
        MetBindInfo<Echo> service = builder.register();
        MetBindInfo<?> aliased = this.container.getRsfBindInfo("http", "echo");
        assertEquals(service.getBindID(), aliased.getBindID());
        assertEquals(List.of("echo"), this.container.getServiceIDs("http"));
    }

    @Test
    public void duplicateServiceDoesNotReplaceOriginalProvider() {
        // 已有提供者的同 ID 登记被拒绝，不能替换原实例或产生额外事件。
        List<String> serviceChanges = new ArrayList<>();
        this.container.subscribe(event -> serviceChanges.add(event.getServiceId()));
        Echo original = new EchoImpl();
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class, original).register();
        assertThrows(IllegalStateException.class, () -> this.publisher.rsfService(Echo.class).register());
        assertSame(original, this.container.getProvider(service).get());
        assertEquals(1, this.container.getServiceIDs().size());
        assertEquals(List.of(service.getBindID()), serviceChanges);
    }

    @Test
    public void concurrentDuplicateRegistrationPublishesOnlyOneService() throws Exception {
        // 四个发布线程从同一闸门起跑，争用同一服务 ID。
        List<String> serviceChanges = new CopyOnWriteArrayList<>();
        this.container.subscribe(event -> serviceChanges.add(event.getServiceId()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        this.container.createPublisher().rsfService(Echo.class).register();
                        return true;
                    } catch (IllegalStateException duplicate) {
                        return false;
                    }
                }));
            }

            // 所有任务都有界完成，只有一个登记和一次通知成功。
            start.countDown();
            int registered = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    registered++;
                }
            }
            assertEquals(1, registered);
            assertEquals(1, this.container.getServiceIDs().size());
            assertEquals(this.container.getServiceIDs(), serviceChanges);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void missingServiceAndAliasesHaveNoProviderOrFilters() {
        // 未登记的 ID 与别名查询使用空结果，不制造服务或过滤器。
        assertNull(this.container.getRsfBindInfo("absent"));
        assertNull(this.container.getRsfBindInfo("absent", "absent"));
        assertTrue(this.container.getServiceIDs("absent").isEmpty());
        assertEquals(0, this.container.getFilterProviders("absent").length);
    }

    @Test
    public void invalidGroupCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.group("bad/group"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.group("fixed");
        MetBindInfo<Echo> valid = builder.register();
        assertEquals("fixed", valid.getBindGroup());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void invalidNameCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.name("bad/name"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.name("echo");
        MetBindInfo<Echo> valid = builder.register();
        assertEquals("echo", valid.getBindName());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void invalidVersionCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.version("bad/version"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.version("1");
        MetBindInfo<Echo> valid = builder.register();
        assertEquals("1", valid.getBindVersion());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void invalidSerializationCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.serialize("bad/type"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.serialize("Java");
        MetBindInfo<Echo> valid = builder.register();
        assertEquals("Java", valid.getSerializeType());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void zeroTimeoutCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.timeout(0));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.timeout(1000);
        MetBindInfo<Echo> valid = builder.register();
        assertEquals(1000, valid.getClientTimeout());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void negativeTimeoutCanBeCorrected() {
        // 当前配置项在装配时拒绝非法值，不得留下已发布服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalStateException.class, () -> builder.timeout(-1));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一 Builder 修正该配置项后可以正常登记。
        builder.timeout(1000);
        MetBindInfo<Echo> valid = builder.register();
        assertEquals(1000, valid.getClientTimeout());
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void invalidServiceRouteCanBeCorrected() {
        // 错误规则在装配时抛出，不能发布静默忽略规则的服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalArgumentException.class, () -> builder.updateServiceRoute("<route/>"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一个 Builder 修正规则后仍可使用。
        builder.updateServiceRoute("<route><return value='addresses'/></route>");
        MetBindInfo<Echo> valid = builder.register();
        assertNotNull(this.container.getRsfBindInfo(valid.getBindID()));
        assertEquals(1, this.container.getServiceIDs().size());
    }

    @Test
    public void invalidFlowControlCanBeCleared() {
        // 错误规则在装配时抛出，不能发布静默忽略规则的服务。
        var builder = this.publisher.rsfService(Echo.class);
        assertThrows(IllegalArgumentException.class, () -> builder.updateFlowControl("<invalid/>"));
        assertTrue(this.container.getServiceIDs().isEmpty());

        // 同一个 Builder 修正规则后仍可使用。
        builder.updateFlowControl("");
        MetBindInfo<Echo> valid = builder.register();
        assertNotNull(this.container.getRsfBindInfo(valid.getBindID()));
        assertEquals(1, this.container.getServiceIDs().size());
    }
}
