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
import java.util.Map;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntAddressBookData;
import net.hasor.meteor.registry.shared.storage.domain.EntData;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
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
            snapshot("z-provider", "Provider").save(provider);
            snapshot("a-consumer", "Consumer").save(consumer);
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            service.putMeta("owner", "team");

            // 服务级查询汇总所有成员，详情优先采用提供者声明。
            assertEquals(1, service.info().getProviderCount());
            assertEquals(1, service.info().getConsumerCount());
            assertEquals("Provider", service.detail().getRole());
            assertArrayEquals(new String[] { "Provider method" }, service.detail().getMethods());

            // 实例级查询只返回指定实例的声明和成员关系。
            MetServiceAdmin scoped = admin.getServiceAdmin("echo", "a-consumer");
            assertEquals(0, scoped.info().getProviderCount());
            assertEquals(1, scoped.info().getConsumerCount());
            assertEquals("Consumer", scoped.detail().getRole());
            assertEquals(0, scoped.providers(null).length);
            assertEquals(1, scoped.consumers(null).length);

            // 实例内禁用不影响服务整体或其他实例，空实例标识被拒绝。
            scoped.disable();
            assertFalse(scoped.isEnable());
            assertTrue(service.isEnable());
            assertTrue(admin.getServiceAdmin("echo", "z-provider").isEnable());
            assertThrows(IllegalArgumentException.class, () -> admin.getServiceAdmin("echo", null));
            assertThrows(IllegalArgumentException.class, () -> admin.getServiceAdmin("echo", " "));

            // 返回详情与内部记录隔离，修改结果不能污染后续查询。
            MetServiceDetail detached = service.detail();
            detached.getMethods()[0] = "modified";
            assertEquals("Provider method", service.detail().getMethods()[0]);

            // 提供者退出后采用订阅者详情；全部成员退出后保留服务目录。
            provider.close();
            assertEquals("Consumer", service.detail().getRole());
            assertEquals(0, service.info().getProviderCount());
            consumer.close();
            assertEquals(0, service.info().getConsumerCount());
            assertEquals("echo", service.info().getServiceId());
            assertEquals(1, admin.getClusterAdmin().services("echo").length);
            assertThrows(IllegalArgumentException.class, service::detail);
        }
    }

    @Test
    public void instanceQueriesAndDesiredSettingsStaySeparateFromReportedState() throws Exception {
        // 准备实例的上报状态、地址本与管理扩展。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.configuration);

        try (MetContext adminContext = adminConfiguration.buildContext(); //
             StorageAccess storage = this.configuration.openStorage()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 写入当前上报状态，包含一个服务与可用地址。
            RegistryRecordFixture snapshot = snapshot("one", "Provider");
            snapshot.getInstance().getSettings().put("reported", "original");
            EntAddressBookData addresses = new EntAddressBookData();
            addresses.setAvailable(new String[] { "rsf://127.0.0.1:8000/default" });
            snapshot.getService("echo").setAddressBook(addresses);
            snapshot.save(storage);
            MetInstanceAdmin instance = admin.getInstanceAdmin("one");

            // 查询服务与地址快照，验证返回数据不能反向修改存储。
            assertEquals(1, instance.getServiceList().length);
            assertEquals(MetServiceInfo.class, instance.getServiceById("echo").getClass());
            assertEquals(1, instance.getServiceById("echo").getProviderCount());
            assertArrayEquals(addresses.getAvailable(), instance.getAddressInfo("echo").getAvailable());
            assertThrows(IllegalArgumentException.class, () -> instance.getServiceById("missing"));
            instance.settings().put("reported", "changed");
            assertEquals("original", instance.settings().get("reported"));

            // 修改期望配置，已上报的实例状态仍保持原值。
            instance.block();
            instance.setRequestOption("trace", "on");
            instance.setResponseOption("result", "managed");
            instance.removeRequestOption("trace");
            assertTrue(instance.isBlock());
            assertFalse(instance.instance().isBlock());
            assertEquals("original", instance.settings().get("reported"));

            // 检查持久配置独立保存，并保留选项删除标记。
            EntityRef owner = RegistryGovernanceUtils.instance("one");
            EntData content = storage.getEntity(owner).getData();
            EntInstanceConfigurationData expected = RegistryJson.copy(content, EntInstanceConfigurationData.class);
            Set<String> configurationFields = Set.of("block", "requestOptions", "responseOptions");
            Map<?, ?> storedFields = RegistryJson.copy(content, Map.class);
            assertEquals(configurationFields, storedFields.keySet());
            assertTrue(expected.getRequestOptions().containsKey("trace"));
            assertNull(expected.getRequestOptions().get("trace"));
            assertEquals("managed", expected.getResponseOptions().get("result"));

            // 实例目录支持按应用名筛选。
            assertEquals(1, admin.getClusterAdmin().instances("orders").length);
            assertEquals(0, admin.getClusterAdmin().instances("other").length);
        }
    }

    @Test
    public void queryingOneServiceDoesNotScanOtherServicesOrInstances() throws Exception {
        // 准备服务成员，并给管理会话安装禁止全库扫描的观察器。
        try (StorageAccess storage = this.configuration.openStorage()) {
            snapshot("one", "Provider").save(storage);
            DecoratedRegistryConfiguration observed = new DecoratedRegistryConfiguration(this.configuration, delegate -> {
                InvocationHandler scopeCheck = (proxy, method, arguments) -> {
                    if (StringUtils.equals(method.getName(), "findEntities")) {
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

                // 查询单个服务的概要、详情及成员，不触发实体目录扫描。
                MetServiceAdmin service = admin.getServiceAdmin("echo");
                assertEquals(1, service.info().getProviderCount());
                assertEquals("Provider", service.detail().getRole());
                assertEquals(1, service.providers(null).length);
                assertEquals(0, service.consumers(null).length);
            }
        }
    }

    private static RegistryRecordFixture snapshot(String instanceId, String role) {
        InstanceData instance = new InstanceData();
        instance.setInstanceId(instanceId);
        instance.setApplicationName("orders");
        ServiceData declaration = new ServiceData();
        declaration.setServiceId("echo");
        declaration.setGroup("group");
        declaration.setName("Echo");
        declaration.setVersion("1.0");
        declaration.setRole(role);
        declaration.setMethods(new String[] { role + " method" });
        ServiceMemberData member = new ServiceMemberData();
        member.setInstance(instance);
        member.setService(declaration);
        return new RegistryRecordFixture(instance, member);
    }
}
