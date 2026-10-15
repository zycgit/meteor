/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.*;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

public class ManagementPublicationTest {
    public interface Echo {
        String echo(String value);

        @MetIgnore
        default String internal() {
            return "internal";
        }
    }

    @Test
    public void conflictingFilterRejectsExtensionInstallation() {
        // 准备离线扩展，并在扩展创建前占用治理过滤器标识。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        MetConfiguration application = new MetConfiguration();
        application.addExtension(context -> {
            // 扩展安装前制造已有过滤器，验证真实 buildContext 的失败路径。
            context.publisher().bindFilter(GovernanceFilter.FILTER_ID, (request, response, chain) -> {
                chain.doFilter(request, response);
            });
            return registry.create(context);
        });

        // 正式构建入口报告过滤器冲突。
        assertThrows(IllegalStateException.class, application::buildContext);
    }

    @Test
    public void existingManagementServiceRejectsExtensionInstallation() {
        // 准备离线扩展，并预先注册同名管理 RPC。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        MetConfiguration application = new MetConfiguration();
        application.addExtension(context -> {
            context.publisher().rsfService(ManagementRPC.class).asShadow().register();
            return registry.create(context);
        });

        // 构建时拒绝重复管理服务并保留具体错误原因。
        IllegalStateException failure = assertThrows(IllegalStateException.class, application::buildContext);
        assertEquals("Management RPC is already registered on this Meteor context", failure.getCause().getMessage());
    }

    @Test
    public void businessRequestsDoNotLookUpManagementServiceWhileFiltering() throws Exception {
        // 准备暂停后台更新的目标应用，以及仅直连的调用方。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registry.setRefreshMillis(600000);
        registry.setPublishHost("127.0.0.1");
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration callerConfig = new MetConfiguration();
        callerConfig.connector("tcp").protocol("rsf");
        callerConfig.addExtension(new RegistryConfiguration().setOnline(false));

        // 扩展仍通过正式工厂创建，只观察其管理服务查询次数。
        AtomicInteger managementLookups = new AtomicInteger();
        serverConfig.addExtension(context -> {
            InvocationHandler counter = (proxy, method, args) -> {
                if (StringUtils.equals(method.getName(), "getServiceInfo")) {
                    boolean managementType = args.length == 1 && args[0] == ManagementRPC.class;
                    if (managementType) {
                        managementLookups.incrementAndGet();
                    }
                }
                if (StringUtils.equals(method.getName(), "getServiceProvider")) {
                    MetBindInfo<?> binding = (MetBindInfo<?>) args[0];
                    if (binding.getBindType() == ManagementRPC.class) {
                        managementLookups.incrementAndGet();
                    }
                }
                try {
                    return method.invoke(context, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            MetContext observed = (MetContext) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class<?>[] { MetContext.class }, counter);
            return registry.create(observed);
        });

        try (MetContext server = serverConfig.buildContext(); //
             MetContext caller = callerConfig.buildContext()) {
            // 启动业务服务与调用方，取得目标的管理代理。
            String id = server.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            MetBindInfo<Echo> binding = caller.publisher().rsfService(Echo.class).register();
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            server.start();
            caller.start();
            MetClient remote = caller.getRsfClient(server.bindAddress("rsf"));
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(server.bindAddress("rsf"));
            MetServiceAdmin service = targets.getServiceAdminByAddress(server.bindAddress("rsf"), id);

            // 正常业务调用不额外查询管理服务。
            int before = managementLookups.get();
            assertEquals("open", remote.getRemote(binding).echo("open"));
            assertEquals(before, managementLookups.get());

            // 实例阻断和服务禁用时，业务过滤也不额外查询管理服务。
            instance.block();
            before = managementLookups.get();
            assertForbidden(() -> remote.getRemote(binding).echo("blocked"));
            assertEquals(before, managementLookups.get());
            instance.unblock();
            service.disable();
            before = managementLookups.get();
            assertForbidden(() -> remote.getRemote(binding).echo("disabled"));
            assertEquals(before, managementLookups.get());

            // 重新启用后业务恢复，查询次数仍保持不变。
            service.enable();
            before = managementLookups.get();
            assertEquals("enabled", remote.getRemote(binding).echo("enabled"));
            assertEquals(before, managementLookups.get());
        }
    }

    @Test
    public void snapshotRespectsServiceProtocolScopeAndDistinguishesUnboundSubscriptions() throws Exception {
        // 准备快照测试使用的注册中心扩展。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = storageConfiguration;
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        registry.setPublishHost("127.0.0.1");
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");

        // 模拟 HTTP 双协议挂载和仅主动连接的 TCP 挂载。
        InterAddress httpAddress = InterAddress.forBinding("http", "0.0.0.0", 0, "default");
        ProtocolConfig hprose = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/api"));
        ProtocolConfig soap = new ProtocolConfig("soap", "soap", "soap", Map.of("contextPath", "/soap"));
        List<ProtocolConfig> httpProtocols = List.of(hprose, soap);
        ConnectorConfig http = new ConnectorConfig("http-0", httpAddress, Map.of("listenType", "http"), httpProtocols, true);
        InterAddress tcpAddress = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        List<ProtocolConfig> tcpProtocols = List.of(new ProtocolConfig("rsf", "rsf", "rsf", Map.of()));
        ConnectorConfig tcp = new ConnectorConfig("tcp-1", tcpAddress, Map.of("listenType", "tcp"), tcpProtocols, false);
        application.addExtension(context -> {
            // 提供不同协议挂载的环境快照；服务声明与治理仍使用真实 Meteor 实现。
            InvocationHandler settingsView = (proxy, method, args) -> {
                if (StringUtils.equals(method.getName(), "getConnectorConfigs")) {
                    return List.of(http, tcp);
                }
                try {
                    return method.invoke(context.getSettings(), args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            MetSettings settings = (MetSettings) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class<?>[] { MetSettings.class }, settingsView);
            InvocationHandler contextView = (proxy, method, args) -> {
                if (StringUtils.equals(method.getName(), "getSettings")) {
                    return settings;
                }
                if (StringUtils.equals(method.getName(), "runProtocols")) {
                    return Set.of("hprose", "soap", "rsf");
                }
                if (StringUtils.equals(method.getName(), "bindAddress")) {
                    if (StringUtils.equals("rsf", (String) args[0])) {
                        return null;
                    }
                    return new InterAddress((String) args[0], "0.0.0.0", 8080, "default");
                }
                try {
                    return method.invoke(context, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            MetContext observed = (MetContext) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class<?>[] { MetContext.class }, contextView);
            return registry.create(observed);
        });

        try (MetContext context = application.buildContext()) {
            // 分别声明限定协议与未限定协议的提供者、订阅者。
            ConfigurationBuilder<Echo> providerBuilder = context.publisher().rsfService(Echo.class, value -> value);
            String provider = providerBuilder.name("provider").protocol("hprose").register().getBindID();
            ConfigurationBuilder<Echo> providersBuilder = context.publisher().rsfService(Echo.class, value -> value);
            String providers = providersBuilder.name("providers").register().getBindID();
            ConfigurationBuilder<Echo> consumerBuilder = context.publisher().rsfService(Echo.class);
            String consumer = consumerBuilder.name("consumer").protocol("rsf").register().getBindID();
            String consumers = context.publisher().rsfService(Echo.class).name("consumers").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 上报后检查管理地址、监听地址及协议支持范围。
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetInstanceInfo instance = admin.getInstanceAdmin(context.getInstanceID()).instance();
            Map<String, String> expected = Map.of("hprose", "hprose://127.0.0.1:8080/default", "soap", "soap://127.0.0.1:8080/default");
            assertEquals(expected, instance.getManagementAddress());
            assertEquals("hprose://0.0.0.0:8080/default", instance.getListenAddresses().get("hprose"));
            assertEquals(Echo.class.getName(), admin.getServiceAdmin(provider).info().getType());
            assertArrayEquals(new String[] { "hprose" }, admin.getServiceAdmin(provider).info().getProtocols());

            // 提供者仅公告所选协议的监听端点。
            MetServiceEndpoint[] published = member(admin, provider).getEndpoints();
            assertEquals(1, published.length);
            assertEquals("/api", published[0].getContextPath());
            assertEquals("http-0", published[0].getConnectorName());
            assertEquals("hprose://0.0.0.0:8080/default", published[0].getListenAddress());
            assertEquals(2, member(admin, providers).getEndpoints().length);

            // 订阅者保留协议挂载信息，但没有监听地址。
            MetServiceEndpoint[] subscribed = member(admin, consumer).getEndpoints();
            assertEquals(1, subscribed.length);
            assertEquals("tcp-1", subscribed[0].getConnectorName());
            assertNull(subscribed[0].getListenAddress());
            assertFalse(subscribed[0].isBindEnabled());
            assertEquals(3, member(admin, consumers).getEndpoints().length);
        }
    }

    @Test
    public void publishesOnlyUsableAddressesAndWithdrawsThemWhenBlockedOrOffline() throws Exception {
        // 准备具有实际监听端口的应用，并暂停自动治理通知。
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration().setApplicationName("orders");
        application.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        application.addExtension(registry);

        try (MetContext context = application.buildContext()) {
            // 构建并声明服务，启动前注册中心尚无实例公告。
            String id = context.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            RegistryClient client = context.getExtension(RegistryClient.class);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(0, admin.getClusterAdmin().instances(null, null).getItems().size());

            // 启动后公告可用的业务地址与管理地址。
            context.start();
            MetInstanceAdmin instance = admin.getInstanceAdmin(context.getInstanceID());
            assertEquals("orders", instance.instance().getApplicationName());
            MetServiceMember published = member(admin, id);
            String address = context.bindAddress("rsf").toString();
            assertArrayEquals(new String[] { address }, published.getAddresses());
            assertEquals(address, published.getEndpoints()[0].getPublishedAddress());
            Map<String, String> management = Map.of("rsf", address);
            assertEquals(management, instance.instance().getManagementAddress());

            // 阻断后撤回业务地址，保留用于管理的地址。
            instance.block();
            client.pullSync().get(5, TimeUnit.SECONDS);
            assertTrue(instance.instance().isBlock());
            assertEquals(management, instance.instance().getManagementAddress());
            assertEquals(0, member(admin, id).getAddresses().length);

            // 解除阻断恢复业务公告；应用下线后监听暂停，业务和管理地址都撤回。
            instance.unblock();
            client.pullSync().get(5, TimeUnit.SECONDS);
            assertEquals(1, member(admin, id).getAddresses().length);
            context.offline();
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertEquals(0, member(admin, id).getAddresses().length);
            assertTrue(instance.instance().getManagementAddress().isEmpty());
        }

        // 退出上下文释放所有扩展会话。
        assertEquals(probe.openedSessions(), probe.closedSessions());
    }

    @Test
    public void discoveryFiltersProtocolsAndInvalidStoredRulesFailThePull() throws Exception {
        // 准备暂停通知的客户端，以及用于模拟外部公告的存储会话。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (MetContext context = application.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            // 启动订阅服务并取得实例管理入口。
            String id = context.publisher().rsfService(Echo.class).register().getBindID();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdmin(context.getInstanceID());

            // 发现结果按支持的协议过滤并去重，旧快照不随后续公告改变。
            String supported = "rsf://127.0.0.1:2181/default";
            publishAddresses(storage, id, supported, supported, "hprose://127.0.0.1:8080/default");
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            List<InterAddress> snapshot = context.getUpdater().queryAvailableAddresses(id);
            assertEquals(List.of(new InterAddress(supported)), snapshot);
            publishAddresses(storage, id);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertTrue(context.getUpdater().queryAvailableAddresses(id).isEmpty());
            assertEquals(List.of(new InterAddress(supported)), snapshot);

            // 实例清单包含实际登记，但不会存储诊断地址本。
            EntServiceRuntimeData current = storage.getInstanceService(context.getInstanceID(), id);
            assertNotNull(current);
            assertFalse(RegistryJson.copy(current, java.util.Map.class).containsKey("addressBooks"));

            // 外部写入无效治理规则时，拉取 Future 保留原始失败。
            EntityRecord<EntRuleConfigurationData> record = new EntityRecord<>();
            EntRuleConfigurationData rule = new EntRuleConfigurationData();
            rule.setRule("<route><unknown/></route>");
            record.setData(rule);
            storage.saveRule(null, id, RuleKind.SERVICE_ROUTE, record);
            Future<Void> pulling = client.pullSync();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pulling.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);

            // 修复规则后重新拉取成功，应用本地默认选项保持原值。
            context.getSettings().getRequestOptions().addOption("owner", "local");
            storage.deleteRule(null, id, RuleKind.SERVICE_ROUTE, storage.getRule(null, id, RuleKind.SERVICE_ROUTE).getRevision());
            client.pullSync().get(5, TimeUnit.SECONDS);
            assertEquals("local", context.getSettings().getRequestOptions().getOption("owner"));
        }
    }

    @Test
    public void discoveryComparesCurrentAddressesWithoutResettingInvalidOrStaticEntries() throws Exception {
        // 准备暂停自动通知的客户端和外部公告会话。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registry.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (MetContext context = application.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            // 启动服务，分别配置静态地址与注册中心发现地址。
            String id = context.publisher().rsfService(Echo.class).register().getBindID();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2181/default");
            InterAddress discovered = new InterAddress("rsf://127.0.0.1:2182/default");
            context.getUpdater().appendStaticAddress(id, List.of(fixed));
            publishAddresses(storage, id, discovered.toString());
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            AddressPool pool = (AddressPool) context.getUpdater();
            pool.invalidAddress(discovered);

            // 上下线事件触发自动核对；后续 push 在同一工作队列中等待核对完成。
            context.offline();
            context.online();
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertTrue(context.getUpdater().queryInvalidAddresses(id).contains(discovered));
            assertTrue(context.getUpdater().queryAllAddresses(id).contains(fixed));

            // 应用主动清空动态地址后，相同发现结果仍须重新应用。
            context.getUpdater().refreshAddress(id, List.of());
            context.offline();
            context.online();
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertTrue(context.getUpdater().queryAvailableAddresses(id).contains(discovered));

            // 主动拉取则会强制重新应用发现结果，可以恢复同一条失效地址。
            pool.invalidAddress(discovered);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertTrue(context.getUpdater().queryAvailableAddresses(id).contains(discovered));

            // 提供者撤回公告后删除动态地址，仍保留静态地址。
            publishAddresses(storage, id);
            context.offline();
            context.online();
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(fixed), context.getUpdater().queryAllAddresses(id));
        }
    }

    private static void assertForbidden(Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (!(failure instanceof MetException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue(failure instanceof MetException);
        assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());
    }

    private static MetServiceMember member(RegistryAdmin admin, String serviceId) {
        MetServiceAdmin service = admin.getServiceAdmin(serviceId);
        List<MetServiceMember> providers = service.providers(null, null).getItems();
        if (!providers.isEmpty()) {
            return providers.get(0);
        }
        return service.consumers(null, null).getItems().get(0);
    }

    /** 模拟外部提供者公告，只有发现测试直接准备存储记录。 */
    private static void publishAddresses(StorageAccess storage, String serviceId, String... addresses) throws Exception {
        EntInstanceRuntimeData runtime = new EntInstanceRuntimeData();
        runtime.setInstanceId("remote");
        runtime.setApplicationName("remote-app");
        runtime.setIpAddress("127.0.0.1");
        storage.registerInstance(runtime);
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId(serviceId);
        service.setRole(MetServiceType.Provider);
        service.setAddresses(addresses);
        storage.registerService("remote", service);
    }
}
