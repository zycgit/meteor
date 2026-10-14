/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceData;
import net.hasor.meteor.registry.shared.rpc.ServiceEndpointData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntApplicationCatalogData;
import net.hasor.meteor.registry.shared.storage.domain.EntConfigurationData;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdminQueryTest {
    private final MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

    @Before
    public void configure() {
        this.configuration.setManagementToken("secret");
    }

    @Test
    public void serviceQueriesKeepTheirScopeAndDirectorySurvivesMembersLeaving() throws Exception {
        // 准备管理扩展，并用独立会话模拟提供者与订阅者。
        MetConfiguration config = new MetConfiguration().addExtension(this.configuration);

        try (MetContext context = config.buildContext(); //
             StorageAccess provider = this.configuration.openStorage(); //
             StorageAccess consumer = this.configuration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 登记两类成员，为服务保存持久治理信息。
            snapshot("z-provider", MetServiceType.Provider, "rsf").save(provider);
            snapshot("a-consumer", MetServiceType.Consumer, "hprose").save(consumer);
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            service.putMeta("owner", "team");

            // 服务级查询汇总目录和所有成员，有提供者时显示提供者角色。
            MetServiceInfo info = service.info();
            assertEquals(1, info.getProviderCount());
            assertEquals(1, info.getConsumerCount());
            assertEquals("example.Echo", info.getType());
            assertEquals(MetServiceType.Provider, info.getRole());
            assertArrayEquals(new String[] { "hprose", "rsf" }, info.getProtocols());

            // 集群列表保留相同的服务信息，协议按当前成员汇总。
            MetServiceInfo listed = admin.getClusterAdmin().services("echo", null).getItems().get(0);
            assertEquals(info.getType(), listed.getType());
            assertEquals(info.getRole(), listed.getRole());
            assertEquals(info.getProviderCount(), listed.getProviderCount());
            assertEquals(info.getConsumerCount(), listed.getConsumerCount());
            assertArrayEquals(info.getProtocols(), listed.getProtocols());

            // 实例级查询只返回指定实例的声明和成员关系。
            MetServiceAdmin scoped = admin.getServiceAdmin("echo", "a-consumer");
            MetServiceInfo local = scoped.info();
            assertEquals(0, local.getProviderCount());
            assertEquals(1, local.getConsumerCount());
            assertEquals(MetServiceType.Consumer, local.getRole());
            assertArrayEquals(new String[] { "hprose" }, local.getProtocols());
            assertEquals(0, scoped.providers(null, null).getItems().size());
            assertEquals(1, scoped.consumers(null, null).getItems().size());
            MetServiceInfo consumerInfo = admin.getInstanceAdmin("a-consumer").getServiceList(null).getItems().get(0);
            assertEquals(local.getType(), consumerInfo.getType());
            assertEquals(local.getRole(), consumerInfo.getRole());
            assertArrayEquals(local.getProtocols(), consumerInfo.getProtocols());

            // 实例内禁用不影响服务整体或其他实例，空实例标识被拒绝。
            scoped.disable();
            assertFalse(scoped.isEnable());
            assertTrue(service.isEnable());
            assertTrue(admin.getServiceAdmin("echo", "z-provider").isEnable());
            assertThrows(IllegalArgumentException.class, () -> admin.getServiceAdmin("echo", null));
            assertThrows(IllegalArgumentException.class, () -> admin.getServiceAdmin("echo", " "));

            // 返回信息与内部记录隔离，修改结果不能污染后续查询。
            MetServiceInfo detached = service.info();
            detached.getProtocols()[0] = "modified";
            assertEquals("hprose", service.info().getProtocols()[0]);

            // 提供者退出后只剩订阅关系；全部成员退出后保留服务目录。
            provider.close();
            assertEquals(MetServiceType.Consumer, service.info().getRole());
            assertEquals(0, service.info().getProviderCount());
            MetServiceInfo remaining = admin.getClusterAdmin().services("echo", null).getItems().get(0);
            assertEquals(MetServiceType.Consumer, remaining.getRole());
            assertArrayEquals(new String[] { "hprose" }, remaining.getProtocols());
            consumer.close();
            MetServiceInfo inactive = service.info();
            assertEquals(0, inactive.getConsumerCount());
            assertEquals("echo", inactive.getServiceId());
            assertEquals("Echo", inactive.getName());
            assertEquals("example.Echo", inactive.getType());
            assertEquals(0, inactive.getProtocols().length);
            assertNull(inactive.getRole());
            List<MetServiceInfo> directory = admin.getClusterAdmin().services("echo", null).getItems();
            assertEquals(1, directory.size());
            assertEquals(inactive.getType(), directory.get(0).getType());
            assertEquals(0, directory.get(0).getProtocols().length);
            assertNull(directory.get(0).getRole());
        }
    }

    @Test
    public void instanceQueriesAndDesiredBlockStaySeparateFromReportedState() throws Exception {
        // 准备实例登记与管理扩展。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.configuration);

        try (MetContext adminContext = adminConfiguration.buildContext(); //
             StorageAccess storage = this.configuration.openStorage()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 仅登记实例、服务目录和关系即可查询本机服务。
            snapshot("one", MetServiceType.Provider, "rsf").save(storage);
            MetInstanceAdmin instance = admin.getInstanceAdmin("one");
            assertEquals(1, instance.getServiceList(null).getItems().size());
            MetServiceInfo service = instance.getServiceById("echo");
            assertEquals(1, service.getProviderCount());
            assertEquals("example.Echo", service.getType());
            assertEquals(MetServiceType.Provider, service.getRole());
            assertArrayEquals(new String[] { "rsf" }, service.getProtocols());
            assertThrows(IllegalArgumentException.class, () -> instance.getServiceById("missing"));

            // 查询结果与持久记录隔离，治理期望值也不会冒充已上报状态。
            instance.instance().setApplicationName("changed");
            assertEquals("orders", instance.instance().getApplicationName());
            instance.block();
            assertTrue(instance.isBlock());
            assertFalse(instance.instance().isBlock());

            // 实例配置仅承载阻断治理，不再保存请求、响应默认选项。
            EntConfigurationData expected = storage.getConfiguration("one", null).getData();
            assertEquals(Boolean.TRUE, expected.getBlock());
            assertNull(expected.getEnabled());

            // 实例目录支持按应用名筛选。
            assertEquals(1, admin.getClusterAdmin().instances("orders", null).getItems().size());
            assertEquals(0, admin.getClusterAdmin().instances("other", null).getItems().size());
        }
    }

    @Test
    public void metadataQueriesKeepGlobalServiceAndInstanceValuesSeparate() throws Exception {
        // 通过正式扩展创建管理端，并登记一个实例服务关系。
        MetConfiguration config = new MetConfiguration().addExtension(this.configuration);
        try (MetContext context = config.buildContext(); //
             StorageAccess storage = this.configuration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            snapshot("one", MetServiceType.Provider, "rsf").save(storage);
            MetClusterAdmin cluster = admin.getClusterAdmin();
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            MetServiceAdmin scoped = admin.getServiceAdmin("echo", "one");

            // 尚未配置时返回空 Map，读取不创建全局或实例服务配置。
            assertTrue(cluster.fetchMeta().isEmpty());
            assertTrue(service.fetchMeta().isEmpty());
            assertTrue(scoped.fetchMeta().isEmpty());
            assertNull(storage.getMetadata(null, null));
            assertNull(storage.getMetadata("one", "echo"));
            MetServiceAdmin missing = admin.getServiceAdmin("missing");
            assertTrue(missing.fetchMeta().isEmpty());
            assertNull(storage.getService("missing"));

            // 各范围只返回自己保存的值，不把全局继承值带入服务编辑。
            cluster.putMeta(Map.of("owner", "global", "globalOnly", true));
            assertTrue(service.fetchMeta().isEmpty());
            service.putMeta(Map.of("owner", "service", "weight", 10));
            assertTrue(scoped.fetchMeta().isEmpty());
            scoped.putMeta("owner", "instance");
            assertEquals(Map.of("owner", "global", "globalOnly", true), cluster.fetchMeta());
            assertEquals(Map.of("owner", "service", "weight", 10), service.fetchMeta());
            assertEquals(Map.of("owner", "instance"), scoped.fetchMeta());

            // 对象、数组和布尔值保持 JSON 类型，返回的嵌套对象也与存储隔离。
            Map<String, Object> structured = Map.of("zones", List.of("east", "west"), "active", true);
            service.putMeta("structured", structured);
            Map<String, Object> fetched = service.fetchMeta();
            assertEquals(structured, fetched.get("structured"));
            ((Map<?, ?>) fetched.get("structured")).clear();
            fetched.clear();
            assertEquals(structured, service.fetchMeta().get("structured"));

            // 更新和删除后立即读到本级存储结果，其他范围不受影响。
            scoped.removeMeta("owner");
            service.putMeta("weight", 20);
            service.removeMeta("structured");
            assertTrue(scoped.fetchMeta().isEmpty());
            assertEquals(Map.of("owner", "service", "weight", 20), service.fetchMeta());
            assertEquals("global", cluster.fetchMeta().get("owner"));
        }
    }

    @Test
    public void queryingOneServiceDoesNotScanOtherServicesOrInstances() throws Exception {
        // 准备服务成员，并给管理会话安装禁止全库扫描的观察器。
        try (StorageAccess storage = this.configuration.openStorage()) {
            snapshot("one", MetServiceType.Provider, "rsf").save(storage);
            DecoratedRegistryConfiguration observed = new DecoratedRegistryConfiguration(this.configuration, delegate -> {
                InvocationHandler scopeCheck = (proxy, method, arguments) -> {
                    if (StringUtils.equals(method.getName(), "instances") || StringUtils.equals(method.getName(), "getInstance")) {
                        throw new AssertionError("A service query must not scan the registry catalogue");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                };
                return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(), new Class<?>[] { StorageAccess.class }, scopeCheck);
            });

            // 通过正式扩展入口创建管理端。
            observed.setManagementToken("secret");
            MetConfiguration adminConfiguration = new MetConfiguration().addExtension(observed);

            try (MetContext adminContext = adminConfiguration.buildContext()) {
                RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

                // 查询单个服务的信息及成员，不触发实体目录扫描。
                MetServiceAdmin service = admin.getServiceAdmin("echo");
                assertEquals(1, service.info().getProviderCount());
                assertEquals(MetServiceType.Provider, service.info().getRole());
                assertEquals(1, service.providers(null, null).getItems().size());
                assertEquals(0, service.consumers(null, null).getItems().size());
            }
        }
    }

    @Test
    public void applicationDirectoryAndMembershipQueriesDoNotScanInstances() throws Exception {
        // 应用存在独立持久记录，即使当前没有实例也能直接列出。
        try (StorageAccess storage = this.configuration.openStorage()) {
            snapshot("one", MetServiceType.Provider, "rsf").save(storage);
            EntApplicationCatalogData empty = new EntApplicationCatalogData();
            empty.setApplicationName("empty-app");
            storage.saveApplication(empty);
            DecoratedRegistryConfiguration observed = new DecoratedRegistryConfiguration(this.configuration, delegate -> {
                InvocationHandler scopeCheck = (proxy, method, arguments) -> {
                    String operation = method.getName();
                    assertFalse("No instance enrichment reads", StringUtils.equals(operation, "getInstance"));
                    if (StringUtils.equals(operation, "instances")) {
                        assertEquals("orders", arguments[0]);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                };
                Class<?>[] contracts = { StorageAccess.class };
                return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(), contracts, scopeCheck);
            });
            observed.setManagementToken("secret");

            // 应用列表仅查询应用；进入应用后只按应用归属索引查询实例。
            try (RegistryAdmin admin = observed.buildAdmin()) {
                MetPage<String> applications = admin.getClusterAdmin().applications(null);
                assertEquals(List.of("empty-app", "orders"), applications.getItems());
                assertNull(applications.getNextCursor());
                List<MetInstanceInfo> instances = admin.getClusterAdmin().instances("orders", null).getItems();
                assertEquals(1, instances.size());
                assertEquals("one", instances.get(0).getInstanceId());
                assertTrue(instances.get(0).isOnline());
            }
        }
    }

    private static RegistryRecordFixture snapshot(String instanceId, MetServiceType role, String protocol) {
        InstanceData instance = new InstanceData();
        instance.setInstanceId(instanceId);
        instance.setApplicationName("orders");
        ServiceData declaration = new ServiceData();
        declaration.setServiceId("echo");
        declaration.setGroup("group");
        declaration.setName("Echo");
        declaration.setVersion("1.0");
        declaration.setRole(role);
        declaration.setType("example.Echo");
        ServiceMemberData member = new ServiceMemberData();
        member.setInstance(instance);
        member.setService(declaration);
        if (role == MetServiceType.Consumer) {
            member.setUsedProtocols(new String[] { protocol });
        }
        ServiceEndpointData endpoint = new ServiceEndpointData();
        endpoint.setProtocolName(protocol);
        endpoint.setProtocol(protocol);
        member.setEndpoints(new ServiceEndpointData[] { endpoint });
        return new RegistryRecordFixture(instance, member);
    }
}
