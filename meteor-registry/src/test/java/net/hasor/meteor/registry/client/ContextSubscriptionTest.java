/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
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
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
                List<MetServiceMember> providers = provided.providers(null, null).getItems();
                return providers.size() == 1 && providers.get(0).getAddresses().length == 1;
            });

            // 等提供者登记完成，再新增消费者，验证统一的登记事件也会触发消费者上报。
            MetBindInfo<Echo> consumer = context.publisher().rsfService(Echo.class).name("consumer").register();
            MetServiceAdmin consumed = admin.getServiceAdmin(consumer.getBindID());
            RegistryTestUtils.await(() -> consumed.consumers(null, null).getItems().size() == 1);

            // 下线撤下可调用地址，上线恢复地址，仍然依靠同一事件订阅。
            context.offline();
            RegistryTestUtils.await(() -> {
                List<MetServiceMember> providers = provided.providers(null, null).getItems();
                return providers.size() == 1 && providers.get(0).getAddresses().length == 0;
            });
            context.online();
            RegistryTestUtils.await(() -> {
                List<MetServiceMember> providers = provided.providers(null, null).getItems();
                return providers.size() == 1 && providers.get(0).getAddresses().length == 1;
            });

            // 关闭依然由扩展生命周期释放会话，临时关系撤销，持久服务目录保留。
            String service = provider.getBindID();
            String instance = context.getInstanceID();
            context.close();
            assertNull(observer.getMember(service, MetServiceType.Provider, instance));
            assertNotNull(observer.getService(service));
        }
    }
}
