/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.container;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.unit.container.ContainerFixtures.Echo;
import org.junit.Test;
import static org.junit.Assert.*;

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
        builder.bindResource(bindResource);
        MetBindInfo<Echo> provider = builder.register();
        assertSame(bindResource, provider.getBindResource());
        MetBindInfo<?> queried = this.container.getRsfBindInfo(provider.getBindID());
        assertSame(bindResource, queried.getBindResource());
        assertTrue(provider.getMetaKeys().isEmpty());

        // 修改治理元数据不会把本地绑定资源变成元数据或替换它。
        provider.setMetaData("revision", 2);
        assertSame(bindResource, provider.getBindResource());
        assertEquals(Set.of("revision"), provider.getMetaKeys());
    }

    @Test
    public void consumerCanBindItsOwnLocalResource() {
        // 消费者声明也可以携带本地资源。
        Object bindResource = new Object();
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindResource(bindResource);
        MetBindInfo<Echo> consumer = builder.register();
        assertSame(bindResource, consumer.getBindResource());
        assertTrue(consumer.getMetaKeys().isEmpty());
    }

    @Test
    public void clearingBindResourceBeforeRegistrationPublishesNoResource() {
        // 同一个配置先绑定再清空，本次发布不携带资源。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindResource(new Object());
        builder.bindResource(null);
        MetBindInfo<Echo> service = builder.register();
        assertNull(service.getBindResource());
    }

    @Test
    public void replacementDoesNotInheritRecoveredBindResource() {
        // 先回收携带资源的提供者，再以同一服务 ID 声明消费者。
        var builder = this.publisher.rsfService(Echo.class, value -> value);
        builder.bindResource(new Object());
        MetBindInfo<Echo> provider = builder.register();
        assertTrue(this.container.recoverService(provider.getBindID()));

        MetBindInfo<Echo> replacement = this.publisher.rsfService(Echo.class).register();
        assertEquals(provider.getBindID(), replacement.getBindID());
        assertNull(replacement.getBindResource());
    }
}
