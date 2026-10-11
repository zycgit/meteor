/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceMember;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.EntityType;
import net.hasor.meteor.registry.shared.storage.domain.RelationType;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextSubscriptionTest {
    @Test
    public void contextEventsPublishServicesAndUpdateAvailabilityWithoutStorageNotifications() throws Exception {
        // 关闭存储通知并延长轮询间隔，确保变化只由 Meteor 事件触发。
        MemoryRegistryConfiguration memory = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(memory, probe::observe);
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        configuration.addExtension(registry);

        try (MetContext context = configuration.buildContext(); //
             StorageAccess observer = memory.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            context.start();

            // 启动后发布提供者，不主动调用 pushSync/pullSync。
            MetBindInfo<Echo> provider = context.publisher().rsfService(Echo.class, value -> value).register();
            MetServiceAdmin provided = admin.getServiceAdmin(provider.getBindID());
            RegistryTestUtils.await(() -> {
                MetServiceMember[] providers = provided.providers(null);
                return providers.length == 1 && providers[0].getAddresses().length == 1;
            });

            // 等提供者登记完成，再新增消费者，验证统一的登记事件也会触发消费者上报。
            MetBindInfo<Echo> consumer = context.publisher().rsfService(Echo.class).name("consumer").register();
            MetServiceAdmin consumed = admin.getServiceAdmin(consumer.getBindID());
            RegistryTestUtils.await(() -> consumed.consumers(null).length == 1);

            // 下线撤下可调用地址，上线恢复地址，仍然依靠同一事件订阅。
            context.offline();
            RegistryTestUtils.await(() -> {
                MetServiceMember[] providers = provided.providers(null);
                return providers.length == 1 && providers[0].getAddresses().length == 0;
            });
            context.online();
            RegistryTestUtils.await(() -> {
                MetServiceMember[] providers = provided.providers(null);
                return providers.length == 1 && providers[0].getAddresses().length == 1;
            });

            // 关闭依然由扩展生命周期释放会话，临时关系撤销，持久服务目录保留。
            EntityRef service = new EntityRef(EntityType.SERVICE, provider.getBindID());
            EntityRef instance = new EntityRef(EntityType.INSTANCE, context.getInstanceID());
            context.close();
            assertNull(observer.getRelation(RelationType.PROVIDES, service, instance));
            assertNotNull(observer.getEntity(service));
            assertEquals(probe.openedSessions(), probe.closedSessions());
        }
    }
}
