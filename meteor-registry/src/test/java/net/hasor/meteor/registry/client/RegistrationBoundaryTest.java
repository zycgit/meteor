/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistrationBoundaryTest {
    @Test
    public void eachInstanceOwnsItsServiceListAndMembersContainTheirIdentity() throws Exception {
        // 三个实例各自发布或订阅两个服务，目录应共享，关系按成员数量增长。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

        try (MetContext first = configuration.buildContext(); //
             MetContext second = configuration.buildContext(); //
             MetContext consumer = configuration.buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            for (String name : new String[] { "first", "second" }) {
                first.publisher().rsfService(Echo.class, value -> value).name(name).register();
                second.publisher().rsfService(Echo.class, value -> value).name(name).register();
                consumer.publisher().rsfService(Echo.class).name(name).register();
            }
            first.start();
            second.start();
            consumer.start();

            // 实例拥有完整服务清单，服务拥有完整成员，不需要关系解引用。
            for (MetContext context : new MetContext[] { first, second, consumer }) {
                String instanceId = context.getInstanceID();
                RecordPage<EntServiceRuntimeData> services = storage.instanceServices(instanceId, new DirectoryQuery());
                assertEquals(2, services.getItems().size());
                EntInstanceRuntimeData data = storage.getInstance(instanceId).getRuntime();
                assertEquals(context.getSettings().getBindAddress(), data.getIpAddress());
                assertEquals(Long.valueOf(ProcessHandle.current().pid()), data.getProcessId());
                for (EntServiceRuntimeData service : services.getItems()) {
                    RefServiceRelationData member = storage.getMember(service.getServiceId(), service.getRole(), instanceId);
                    assertEquals(data.getIpAddress(), member.getInstance().getIpAddress());
                    assertEquals(service.getType(), member.getService().getType());
                }
            }

            // 集群和实例两个方向的查询都以这些关系为依据。
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            List<MetServiceInfo> services = admin.getClusterAdmin().services(null, null).getItems();
            assertEquals(2, services.size());
            for (MetServiceInfo service : services) {
                assertEquals(2, service.getProviderCount());
                assertEquals(1, service.getConsumerCount());
                assertEquals(2, admin.getServiceAdmin(service.getServiceId()).providers(null, null).getItems().size());
            }
            assertEquals(2, admin.getInstanceAdmin(first.getInstanceID()).getServiceList(null).getItems().size());

            // 成员退出只清理其关系，服务目录仍保留，其他实例仍可发现。
            String firstId = first.getInstanceID();
            first.close();
            assertTrue(storage.instanceServices(firstId, new DirectoryQuery()).getItems().isEmpty());
            assertNull(storage.getInstance(firstId).getRuntime());
            assertEquals(3, storage.instances("application", new DirectoryQuery()).getItems().size());
            for (MetServiceInfo service : admin.getClusterAdmin().services(null, null).getItems()) {
                assertEquals(1, service.getProviderCount());
                assertEquals(1, service.getConsumerCount());
            }
        }
    }

    @Test
    public void registrationNeverCollectsRuntimeConfigurationOrAddressDiagnostics() throws Exception {
        // 在正式扩展入口装饰公开接口，诊断读取一旦发生就立即失败。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.addExtension(context -> {
            String[] addressMethods = { "queryAvailableAddresses", "queryInvalidAddresses", "queryLocalUnitAddresses" };
            Set<String> addressReads = Set.of(addressMethods);
            Set<String> settingReads = Set.of("getDefaultTimeout", "getRequestTimeout", "getRequestOptions");
            Set<String> declarationReads = Set.of("getClientTimeout", "getSerializeType", "getAliasTypes");
            MetUpdater updater = rejectReads(MetUpdater.class, context.getUpdater(), addressReads);
            MetSettings settings = rejectReads(MetSettings.class, context.getSettings(), settingReads);
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (StringUtils.equals(method.getName(), "getUpdater")) {
                    return updater;
                }
                if (StringUtils.equals(method.getName(), "getSettings")) {
                    return settings;
                }
                try {
                    Object value = method.invoke(context, arguments);
                    if (value instanceof MetBindInfo<?> binding) {
                        return rejectReads(MetBindInfo.class, binding, declarationReads);
                    }
                    return value;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            Class<?>[] interfaces = { MetContext.class };
            Object proxy = Proxy.newProxyInstance(context.getClassLoader(), interfaces, handler);
            MetContext observed = (MetContext) proxy;
            return registry.create(observed);
        });

        try (MetContext context = configuration.buildContext()) {
            // 启动、全量推送和单服务推拉均只使用登记和治理所需的 API。
            String id = context.publisher().rsfService(Echo.class).register().getBindID();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            client.pushSync().get(5, TimeUnit.SECONDS);
            client.pushSync(id).get(5, TimeUnit.SECONDS);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(1, admin.getServiceAdmin(id).consumers(null, null).getItems().size());
        }
    }

    private static <T> T rejectReads(Class<T> type, T target, Set<String> forbidden) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (forbidden.contains(method.getName())) {
                throw new AssertionError("Registration must not collect " + method.getName());
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        Object observed = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
        return type.cast(observed);
    }
}
