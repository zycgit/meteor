/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetPage;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.InstanceStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersistentDirectoryTest {
    @Test
    public void shutdownRetainsApplicationInstanceAndServiceAcrossAdminSessions() throws Exception {
        // 正式扩展发布一个服务，管理端使用独立存储会话。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        MetConfiguration config = new MetConfiguration().setApplicationName("orders").addExtension(registry);
        config.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        String instanceId;
        String serviceId;
        String ip;
        try (RegistryAdmin admin = registry.buildAdmin(); //
             MetContext application = config.buildContext()) {
            serviceId = application.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            instanceId = application.getInstanceID();
            application.start();
            MetInstanceInfo online = admin.getInstanceAdmin(instanceId).instance();
            ip = online.getIpAddress();
            assertTrue(online.isOnline());
            MetPage<String> applications = admin.getClusterAdmin().applications(null);
            assertEquals(List.of("orders"), applications.getItems());
            assertNull(applications.getNextCursor());
            admin.getInstanceAdmin(instanceId).block();

            // 正常停机删除运行会话和成员关系，保留身份档案及治理。
            application.close();
            MetInstanceInfo offline = admin.getInstanceAdmin(instanceId).instance();
            assertFalse(offline.isOnline());
            assertEquals(ip, offline.getIpAddress());
            assertEquals(online.getProcessId(), offline.getProcessId());
            assertTrue(offline.getManagementAddress().isEmpty());
            assertTrue(admin.getInstanceAdmin(instanceId).isBlock());
            assertEquals(0, admin.getInstanceAdmin(instanceId).getServiceList(null).getItems().size());
            assertEquals(0, admin.getServiceAdmin(serviceId).providers(null, null).getItems().size());
        }

        // 重新打开管理端，目录来自存储，不依赖旧管理端的内存缓存。
        try (RegistryAdmin reopened = registry.buildAdmin(); //
             StorageAccess storage = registry.openStorage()) {
            MetPage<String> applications = reopened.getClusterAdmin().applications(null);
            assertEquals(List.of("orders"), applications.getItems());
            assertNull(applications.getNextCursor());
            List<MetInstanceInfo> instances = reopened.getClusterAdmin().instances("orders", null).getItems();
            assertEquals(1, instances.size());
            assertEquals(instanceId, instances.get(0).getInstanceId());
            assertEquals(ip, instances.get(0).getIpAddress());
            assertFalse(instances.get(0).isOnline());
            List<MetServiceInfo> services = reopened.getClusterAdmin().services(null, null).getItems();
            assertEquals(serviceId, services.get(0).getServiceId());
            InstanceStatus catalog = storage.getInstance(instanceId);
            assertNotNull(catalog.getInstance());
            assertNull(catalog.getRuntime());
        }
    }

    @Test
    public void lostStorageSessionMarksInstanceOfflineAndWithdrawsDiscovery() throws Exception {
        // 装饰真实内存存储，仅用于终止应用的注册会话，不调用应用关闭流程。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(50);
        AtomicReference<StorageAccess> session = new AtomicReference<>();
        RegistryConfiguration observed = new DecoratedRegistryConfiguration(registry, storage -> {
            session.set(storage);
            return storage;
        });
        observed.setRefreshMillis(600000);
        MetConfiguration providerConfig = new MetConfiguration().setApplicationName("orders").addExtension(observed);
        providerConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration consumerConfig = new MetConfiguration().setApplicationName("caller").addExtension(registry);
        consumerConfig.connector("tcp").protocol("rsf");

        try (RegistryAdmin admin = registry.buildAdmin(); //
             MetContext provider = providerConfig.buildContext(); //
             MetContext consumer = consumerConfig.buildContext()) {
            String id = provider.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            consumer.publisher().rsfService(Echo.class).register();
            provider.start();
            consumer.start();
            RegistryTestUtils.await(() -> !consumer.getUpdater().queryAllAddresses(id).isEmpty());

            // 会话消失即离线，消费者自动移除地址，但三个主对象仍能查询。
            session.get().close();
            RegistryTestUtils.await(() -> consumer.getUpdater().queryAllAddresses(id).isEmpty());
            MetInstanceInfo instance = admin.getInstanceAdmin(provider.getInstanceID()).instance();
            assertFalse(instance.isOnline());
            assertEquals("orders", instance.getApplicationName());
            assertEquals(1, admin.getClusterAdmin().instances("orders", null).getItems().size());
            MetPage<String> applications = admin.getClusterAdmin().applications(null);
            assertEquals(List.of("caller", "orders"), applications.getItems());
            assertNull(applications.getNextCursor());
            assertEquals(1, admin.getClusterAdmin().services(id, null).getItems().size());
            assertEquals(0, admin.getServiceAdmin(id).providers(null, null).getItems().size());
        }
    }
}
