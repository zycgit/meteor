/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.container;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.ContainerFixtures.Echo;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MetContainerResourceTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void providerBindResourceSurvivesLookupAndMetadataChange() {
        // 发布携带本地绑定资源的提供者，查询保持对象身份。
        Object bindResource = new Object();
        var builder = this.publisher.rsfService(Echo.class, value -> value);
        builder.bindResource("rsf", bindResource);
        MetBindInfo<Echo> provider = builder.register();
        assertSame(bindResource, MetBindInfoWrap.unwrap(provider).getProtocolMeta("rsf").getBindResource());
        MetBindInfo<?> queried = this.container.getRsfBindInfo(provider.getBindID());
        assertSame(bindResource, MetBindInfoWrap.unwrap(queried).getProtocolMeta("rsf").getBindResource());
        assertTrue(provider.getMetaKeys().isEmpty());

        // 修改治理元数据不会把本地绑定资源变成元数据或替换它。
        provider.setMetaData("revision", 2);
        assertSame(bindResource, MetBindInfoWrap.unwrap(provider).getProtocolMeta("rsf").getBindResource());
        assertEquals(Set.of("revision"), provider.getMetaKeys());
    }

    @Test
    public void consumerCanBindItsOwnLocalResource() {
        // 消费者声明也可以携带本地资源。
        Object bindResource = new Object();
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindResource("rsf", bindResource);
        MetBindInfo<Echo> consumer = builder.register();
        assertSame(bindResource, MetBindInfoWrap.unwrap(consumer).getProtocolMeta("rsf").getBindResource());
        assertTrue(consumer.getMetaKeys().isEmpty());
    }

    @Test
    public void nullResourceClearsTheBindingBeforeRegistration() {
        // 先设置资源，再显式清空同一协议的资源声明。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindResource("rsf", new Object());

        builder.bindResource("rsf", null);
        MetBindInfo<Echo> service = builder.register();
        assertNull(MetBindInfoWrap.unwrap(service).getProtocolMeta("rsf"));
    }

    @Test
    public void newResourceReplacesThePreviousBinding() {
        // 同一协议重复设置时采用最后一次提供的对象。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindResource("rsf", new Object());
        Object replacement = new Object();

        builder.bindResource("rsf", replacement);
        MetBindInfo<Echo> service = builder.register();
        assertSame(replacement, MetBindInfoWrap.unwrap(service).getProtocolMeta("rsf").getBindResource());
    }

    @Test
    public void nullProtocolCannotBindResource() {
        // 协议名不能缺失，即使资源本身已经提供。
        var builder = this.publisher.rsfService(Echo.class);

        assertThrows(IllegalArgumentException.class, () -> builder.bindResource(null, new Object()));
    }

    @Test
    public void emptyProtocolCannotBindResource() {
        // 空字符串不能作为资源绑定的协议名。
        var builder = this.publisher.rsfService(Echo.class);

        assertThrows(IllegalArgumentException.class, () -> builder.bindResource("", new Object()));
    }

    @Test
    public void blankProtocolCannotBindResource() {
        // 资源绑定必须明确指定所属协议。
        var builder = this.publisher.rsfService(Echo.class);

        assertThrows(IllegalArgumentException.class, () -> builder.bindResource(" ", new Object()));
    }

    @Test
    public void unconfiguredProtocolCannotBindResource() {
        // 当前实例只配置 RSF，其他协议名不能借资源绑定隐式启用。
        var builder = this.publisher.rsfService(Echo.class);

        assertThrows(IllegalArgumentException.class, () -> builder.bindResource("unconfigured", new Object()));
    }

    @Test
    public void clearingResourceStillRequiresAProtocolName() {
        // 清空操作也必须定位到明确的协议，不能跳过协议名校验。
        var builder = this.publisher.rsfService(Echo.class);

        assertThrows(IllegalArgumentException.class, () -> builder.bindResource(" ", null));
    }

    @Test
    public void replacementDoesNotInheritRecoveredBindResource() {
        // 先回收携带资源的提供者，再以同一服务 ID 声明消费者。
        var builder = this.publisher.rsfService(Echo.class, value -> value);
        builder.bindResource("rsf", new Object());
        MetBindInfo<Echo> provider = builder.register();
        assertTrue(this.container.recoverService(provider.getBindID()));

        MetBindInfo<Echo> replacement = this.publisher.rsfService(Echo.class).register();
        assertEquals(provider.getBindID(), replacement.getBindID());
        assertNull(MetBindInfoWrap.unwrap(replacement).getProtocolMeta("rsf"));
    }
}
