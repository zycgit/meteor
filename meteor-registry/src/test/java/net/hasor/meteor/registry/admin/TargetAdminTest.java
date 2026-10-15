/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntMetadataData;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实 TCP RPC，验证直连管理与存储管理的独立性。 */
public class TargetAdminTest {
    @Test
    public void directManagementNeedsNoCallerStorageAndCanRecoverBlockedBusinessCalls() throws Exception {
        // 准备两个接入同一注册中心的服务端，暂停自动治理更新。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registryConfiguration = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfiguration.setManagementToken("secret");
        registryConfiguration.setRefreshMillis(600000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfiguration);

        // 调用方使用离线注册中心扩展，只通过 RPC 管理目标。
        MetConfiguration callerConfiguration = new MetConfiguration();
        callerConfiguration.addExtension(new RegistryConfiguration().setOnline(false));
        callerConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");

        try (MetContext first = serverConfiguration.setApplicationName("orders").buildContext(); //
             MetContext second = serverConfiguration.setApplicationName("inventory").buildContext(); //
             MetContext caller = callerConfiguration.buildContext()) {
            // 分别发布业务服务，启动各上下文并完成初始拉取。
            String id = first.publisher().rsfService(Echo.class, value -> "first:" + value).register().getBindID();
            second.publisher().rsfService(Echo.class, value -> "second:" + value).register();
            caller.publisher().rsfService(Echo.class).register();
            first.start();
            RegistryClient firstClient = first.getExtension(RegistryClient.class);
            second.start();
            RegistryClient secondClient = second.getExtension(RegistryClient.class);
            caller.start();
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            firstClient.pullSync().get(5, TimeUnit.SECONDS);
            secondClient.pullSync().get(5, TimeUnit.SECONDS);

            // 按目标地址取得管理入口，调用方不创建存储会话。
            InterAddress address = first.bindAddress("rsf");
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(address);
            MetServiceAdmin service = targets.getServiceAdminByAddress(address, id);
            MetServiceAdmin other = targets.getServiceAdminByAddress(second.bindAddress("rsf"), id);
            assertEquals(2, probe.openedSessions());

            // 查询目标的应用、服务和端点信息。
            MetBindInfo<ManagementRPC> management = first.getServiceInfo(ManagementRPC.class);
            assertTrue(management.isShadow());
            assertEquals(first.getInstanceID(), instance.instance().getInstanceId());
            assertEquals("orders", instance.instance().getApplicationName());
            assertEquals(Map.of("rsf", address.toString()), instance.instance().getManagementAddress());
            MetServiceMember otherProvider = other.providers(null, null).getItems().get(0);
            assertEquals("inventory", otherProvider.getInstance().getApplicationName());
            MetPage<MetServiceInfo> services = instance.getServiceList(null);
            assertEquals(1, services.getItems().size());
            assertNull(services.getNextCursor());
            MetServiceInfo info = service.info();
            assertEquals(id, info.getServiceId());
            assertEquals(Echo.class.getName(), info.getType());
            assertEquals(MetServiceType.Provider, info.getRole());
            assertArrayEquals(new String[] { "rsf" }, info.getProtocols());
            MetServiceInfo found = instance.getServiceById(id);
            assertEquals(info.getServiceId(), found.getServiceId());
            assertEquals(info.getType(), found.getType());
            assertEquals(info.getRole(), found.getRole());
            assertArrayEquals(info.getProtocols(), found.getProtocols());
            MetServiceInfo listed = services.getItems().get(0);
            assertEquals(info.getServiceId(), listed.getServiceId());
            assertEquals(info.getType(), listed.getType());
            assertEquals(info.getRole(), listed.getRole());
            assertArrayEquals(info.getProtocols(), listed.getProtocols());
            MetPage<MetServiceMember> providers = service.providers(null, null);
            assertEquals(1, providers.getItems().size());
            assertNull(providers.getNextCursor());
            MetPage<MetServiceMember> consumers = service.consumers(null, null);
            assertTrue(consumers.getItems().isEmpty());
            assertNull(consumers.getNextCursor());

            // 端点筛选只针对目标实例，业务 RPC 初始可正常调用。
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setTransport("tcp");
            assertEquals(1, service.providers(filter, null).getItems().size());
            filter.setInstanceId(second.getInstanceID());
            MetPage<MetServiceMember> unmatched = service.providers(filter, null);
            assertTrue(unmatched.getItems().isEmpty());
            assertNull(unmatched.getNextCursor());
            assertEquals("first:ok", caller.getRsfClient(address).wrapper(Echo.class).echo("ok"));

            // 禁用目标服务只影响网络调用，本地调用和其他实例不受影响。
            service.disable();
            assertFalse(service.isEnable());
            assertTrue(other.isEnable());
            assertForbidden(() -> caller.getRsfClient(address).wrapper(Echo.class).echo("blocked"));
            assertEquals("first:local", first.getRsfClient().wrapper(Echo.class).echo("local"));
            service.enable();
            assertEquals("first:back", caller.getRsfClient(address).wrapper(Echo.class).echo("back"));

            // 实例阻断覆盖网络业务 RPC，默认本地优先调用仍可执行。
            instance.block();
            assertTrue(instance.isBlock());
            assertTrue(instance.instance().isBlock());
            assertEquals(Map.of("rsf", address.toString()), instance.instance().getManagementAddress());
            assertTrue(first.isOnline());
            assertForbidden(() -> caller.getRsfClient(address).wrapper(Echo.class).echo("blocked"));
            InterAddress secondAddress = second.bindAddress("rsf");
            assertEquals("first:blocked", first.getRsfClient(secondAddress).wrapper(Echo.class).echo("blocked"));
            instance.unblock();
            assertEquals("first:back", caller.getRsfClient(address).wrapper(Echo.class).echo("back"));

            // 关闭一个目标后其管理调用失败，其他上下文仍然可用。
            first.close();
            assertThrows(Throwable.class, instance::instance);
            assertThrows(Throwable.class, service::info);
            assertThrows(Throwable.class, service::fetchMeta);
            assertTrue(caller.isOnline());
            assertFalse(first.isOnline());
            assertEquals(id, other.info().getServiceId());
        }
    }

    @Test
    public void directQueriesUseLocalEndpointsAndAlwaysCallTheSelectedTarget() throws Exception {
        // 准备共享注册中心的提供者与订阅者。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registryConfiguration = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfiguration.setManagementToken("secret");
        registryConfiguration.setRefreshMillis(600000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfiguration);

        // 提供者监听所有网卡，但公告使用明确的发布主机。
        MetConfiguration config = new MetConfiguration();
        config.connector("tcp").bind("0.0.0.0", 0).protocol("rsf");
        RegistryConfiguration registryConfig = registryConfiguration.setPublishHost("127.0.0.1");
        config.addExtension(registryConfig);

        try (MetContext provider = config.buildContext(); //
             MetContext consumer = serverConfiguration.buildContext()) {
            // 启动双方，由同样发布了管理 RPC 的提供者发起直连查询。
            String id = provider.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            consumer.publisher().rsfService(Echo.class).register();
            provider.start();
            consumer.start();
            TargetAdmin targets = provider.getExtension(TargetAdmin.class);
            assertTrue(provider.getServiceInfo(ManagementRPC.class).isDistributed());

            // 管理服务显式使用分布式模式，按地址调用访问指定对端。
            InterAddress remote = consumer.bindAddress("rsf");
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(remote);
            MetServiceAdmin service = targets.getServiceAdminByAddress(remote, id);
            assertEquals(consumer.getInstanceID(), instance.instance().getInstanceId());
            assertEquals(1, service.info().getConsumerCount());
            assertEquals(MetServiceType.Consumer, service.info().getRole());
            MetServiceInfo consumerInfo = instance.getServiceList(null).getItems().get(0);
            assertEquals(Echo.class.getName(), consumerInfo.getType());
            assertEquals(MetServiceType.Consumer, consumerInfo.getRole());
            assertArrayEquals(new String[] { "rsf" }, consumerInfo.getProtocols());
            assertEquals(0, service.providers(null, null).getItems().size());
            MetPage<MetServiceMember> consumers = service.consumers(null, null);
            assertEquals(1, consumers.getItems().size());
            assertNull(consumers.getNextCursor());

            // 直连查询返回实际监听地址，不把注册中心的发布地址混入本地端点。
            InterAddress bound = provider.bindAddress("rsf");
            InterAddress local = new InterAddress("rsf", "127.0.0.1", bound.getPort(), bound.getFormUnit());
            MetServiceAdmin localService = targets.getServiceAdminByAddress(local, id);
            assertEquals(id, localService.info().getServiceId());
            MetServiceMember member = localService.providers(null, null).getItems().get(0);
            assertEquals(Map.of("rsf", local.toString()), member.getInstance().getManagementAddress());
            assertEquals(provider.bindAddress("rsf").toString(), member.getEndpoints()[0].getListenAddress());
            assertNull(member.getEndpoints()[0].getPublishedAddress());
            assertEquals(2, probe.openedSessions());
        }
    }

    @Test
    public void rulesAndMetadataChangeOnlyTheTargetRuntime() throws Exception {
        // 准备目标应用的注册中心扩展，禁止后台治理覆盖直连操作。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registryConfiguration = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfiguration.setManagementToken("secret");
        registryConfiguration.setRefreshMillis(600000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfiguration);

        // 调用方只启用直连管理，目标规则在用例中逐步修改。
        MetConfiguration callerConfiguration = new MetConfiguration();
        callerConfiguration.addExtension(new RegistryConfiguration().setOnline(false));
        callerConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");

        String baseline = "<route><return value='addresses'/></route>";
        String instanceRule = "<route><!-- override --><return value='addresses'/></route>";
        String serviceRule = "<route><return value='{}'/></route>";

        try (MetContext target = serverConfiguration.buildContext(); //
             MetContext caller = callerConfiguration.buildContext()) {
            // 发布带初始规则的服务，启动双方并完成一次初始拉取。
            ConfigurationBuilder<Echo> serviceBuilder = target.publisher().rsfService(Echo.class, value -> value);
            MetBindInfo<Echo> binding = serviceBuilder.updateServiceRoute(baseline).register();
            target.start();
            RegistryClient client = target.getExtension(RegistryClient.class);
            caller.start();
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            client.pullSync().get(5, TimeUnit.SECONDS);
            InterAddress address = target.bindAddress("rsf");
            String id = binding.getBindID();
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(address);
            MetServiceAdmin service = targets.getServiceAdminByAddress(address, id);

            // 实例规则写入当前服务；需要存储保留正文的启停操作不受支持。
            instance.updateRule(MetRuleType.SERVICE_ROUTE, instanceRule);
            assertEquals(instanceRule, target.getUpdater().serviceRoute(id));
            assertEquals(instanceRule, instance.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertRuleSwitchUnsupported(() -> instance.disableRule(MetRuleType.SERVICE_ROUTE));
            assertRuleSwitchUnsupported(() -> instance.enableRule(MetRuleType.SERVICE_ROUTE));
            assertRuleSwitchUnsupported(() -> instance.isRuleEnabled(MetRuleType.SERVICE_ROUTE));
            assertEquals(instanceRule, target.getUpdater().serviceRoute(id));

            // 服务规则覆盖当前规则，查询与重置均读取应用实际状态。
            service.updateRule(MetRuleType.SERVICE_ROUTE, serviceRule);
            assertEquals(serviceRule, target.getUpdater().serviceRoute(id));
            assertRuleSwitchUnsupported(() -> service.disableRule(MetRuleType.SERVICE_ROUTE));
            assertRuleSwitchUnsupported(() -> service.enableRule(MetRuleType.SERVICE_ROUTE));
            assertTrue(service.isRule(MetRuleType.SERVICE_ROUTE));

            // 默认流控不代表配置过脚本，也没有隐含的引擎名称。
            assertFalse(service.isRule(MetRuleType.FLOW_CONTROL));
            assertEquals(serviceRule, service.fetchRule(MetRuleType.SERVICE_ROUTE));
            service.resetRule(MetRuleType.SERVICE_ROUTE);
            assertNull(target.getUpdater().serviceRoute(id));
            assertFalse(service.isRule(MetRuleType.SERVICE_ROUTE));
            service.updateRule(MetRuleType.SERVICE_ROUTE, " \t ");
            assertFalse(service.isRule(MetRuleType.SERVICE_ROUTE));
            instance.updateRule(MetRuleType.SERVICE_ROUTE, instanceRule);
            instance.resetRule(MetRuleType.SERVICE_ROUTE);
            assertNull(target.getUpdater().serviceRoute(id));

            // 无效规则在校验与写入时均被拒绝。
            assertFalse(service.validateRule(MetRuleType.FLOW_CONTROL, "<invalid/>").isValid());
            assertThrows(Throwable.class, () -> service.updateRule(MetRuleType.FLOW_CONTROL, "<invalid/>"));

            // 查询实时读取应用元信息，包含应用自行设置的值。
            assertTrue(service.fetchMeta().isEmpty());
            binding.setMetaData("owner", "base");
            binding.setMetaData("application", Map.of("region", "east"));
            Map<String, Object> fetched = service.fetchMeta();
            assertEquals("base", fetched.get("owner"));
            assertEquals(Map.of("region", "east"), fetched.get("application"));
            ((Map<?, ?>) fetched.get("application")).clear();
            fetched.clear();
            assertEquals(Map.of("region", "east"), service.fetchMeta().get("application"));

            // 元信息支持单键、批量写入及多键删除，查询随应用变化。
            service.putMeta("owner", "direct");
            service.putMeta(Map.of("zone", "local", "limit", 12));
            assertEquals("direct", binding.getMetaData("owner"));
            assertEquals(12, binding.getMetaData("limit"));
            assertEquals("direct", service.fetchMeta().get("owner"));
            assertEquals(12, service.fetchMeta().get("limit"));
            service.removeMeta("owner", "zone");
            assertNull(binding.getMetaData("owner"));
            assertNull(binding.getMetaData("zone"));
            assertFalse(service.fetchMeta().containsKey("owner"));
            binding.setMetaData("limit", 18);
            assertEquals(18, service.fetchMeta().get("limit"));
            MetServiceAdmin missing = targets.getServiceAdminByAddress(address, "missing");
            assertThrows(Throwable.class, missing::fetchMeta);

            // 超限元信息和无效目标参数明确报错，不留下部分写入。
            Map<String, Object> overflow = new LinkedHashMap<>();
            for (int i = 0; i <= MetBindInfo.MAX_METADATA_SIZE; i++) {
                overflow.put("key" + i, i);
            }
            assertThrows(Throwable.class, () -> service.putMeta(overflow));
            assertNull(binding.getMetaData("key0"));
            assertThrows(Throwable.class, () -> instance.getServiceById("missing"));
            assertThrows(IllegalArgumentException.class, () -> targets.getServiceAdminByAddress(address, " "));
            assertThrows(NullPointerException.class, () -> targets.getInstanceAdminByAddress(null));

            // 新服务不继承历史直连修改；再次实例级更新才覆盖当前所有服务。
            instance.updateRule(MetRuleType.SERVICE_ROUTE, instanceRule);
            String late = target.publisher().rsfService(Echo.class, value -> value).name("late").register().getBindID();
            assertNull(target.getUpdater().serviceRoute(late));
            assertEquals(2, instance.getServiceList(null).getItems().size());
            client.pullSync().get(5, TimeUnit.SECONDS);
            service.updateRule(MetRuleType.SERVICE_ROUTE, serviceRule);
            assertThrows(Throwable.class, () -> instance.fetchRule(MetRuleType.SERVICE_ROUTE));
            instance.updateRule(MetRuleType.SERVICE_ROUTE, instanceRule);
            assertEquals(instanceRule, instance.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(instanceRule, target.getUpdater().serviceRoute(late));

            // 目标关闭后，已有管理代理的查询均失败。
            target.close();
            assertThrows(Throwable.class, instance::instance);
            assertThrows(Throwable.class, () -> service.isRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    @Test
    public void directChangesDoNotWriteStorageOrOverrideLaterPulls() throws Exception {
        // 准备可暂停订阅、可注入故障的目标注册中心。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registryConfiguration = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfiguration.setManagementToken("secret");
        registryConfiguration.setRefreshMillis(600000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfiguration);

        // 调用方使用离线扩展，管理操作全部直连目标。
        MetConfiguration callerConfiguration = new MetConfiguration();
        callerConfiguration.addExtension(new RegistryConfiguration().setOnline(false));
        callerConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");

        RegistryConfiguration bootstrap = registryConfiguration.setManagementToken("secret");

        try (MetContext target = serverConfiguration.buildContext(); //
             MetContext caller = callerConfiguration.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            // 启动业务服务、调用方和独立的存储管理上下文。
            MetBindInfo<Echo> binding = target.publisher().rsfService(Echo.class, value -> value).register();
            caller.publisher().rsfService(Echo.class).register();
            String id = binding.getBindID();
            target.start();
            caller.start();
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            MetConfiguration adminConfiguration = new MetConfiguration().addExtension(bootstrap);

            try (MetContext adminContext = adminConfiguration.buildContext()) {
                RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
                RegistryClient client = target.getExtension(RegistryClient.class);
                MetServiceAdmin stored = admin.getServiceAdmin(id);

                // 先写入并拉取注册中心配置，作为直连修改前的基准。
                stored.putMeta("owner", "registry");
                admin.getInstanceAdmin(target.getInstanceID()).unblock();
                client.pullSync().get(5, TimeUnit.SECONDS);

                // 直连修改只作用于目标当前状态。
                MetServiceAdmin direct = targets.getServiceAdminByAddress(target.bindAddress("rsf"), id);
                MetInstanceAdmin instance = targets.getInstanceAdminByAddress(target.bindAddress("rsf"));
                direct.putMeta("owner", "direct");
                instance.block();
                assertEquals("direct", binding.getMetaData("owner"));
                assertTrue(instance.isBlock());

                // 再次拉取恢复注册中心配置，存储未被直连操作污染。
                client.pullSync().get(5, TimeUnit.SECONDS);
                assertEquals("registry", binding.getMetaData("owner"));
                assertFalse(instance.isBlock());
                EntMetadataData data = storage.getMetadata(null, id).getData();
                assertEquals("registry", data.getMetadata().get("owner"));
                assertEquals(1, admin.getClusterAdmin().services(null, null).getItems().size());

                // 注册中心故障时，直连查询和治理仍然可用。
                probe.setUnavailable(true);
                try {
                    instance.unblock();
                    assertFalse(instance.isBlock());
                    assertEquals(id, direct.info().getServiceId());
                    assertThrows(IllegalStateException.class, stored::info);
                } finally {
                    probe.setUnavailable(false);
                }

                // 恢复存储后仍可直连删除；目标关闭后管理调用失败。
                direct.removeMeta("owner");
                assertNull(binding.getMetaData("owner"));
                target.close();
                assertThrows(Throwable.class, instance::instance);
                assertFalse(target.isOnline());
            }
        }
    }

    @Test
    public void directManagementWorksWithoutRegistryOnEitherSide() throws Exception {
        // 双方均使用离线扩展，只有目标应用开启监听。
        RegistryConfiguration serverExtension = new RegistryConfiguration().setOnline(false);
        serverExtension.setPublishHost("127.0.0.1");
        MetConfiguration server = new MetConfiguration().addExtension(serverExtension);
        server.connector("tcp").bind("0.0.0.0", 0).protocol("rsf").option("serializeType", "Json");
        MetConfiguration client = new MetConfiguration();
        client.addExtension(new RegistryConfiguration().setOnline(false));
        client.connector("tcp").protocol("rsf").option("serializeType", "Json");

        try (MetContext target = server.buildContext(); //
             MetContext caller = client.buildContext()) {
            // 启动业务服务与调用方，确认双方均不提供存储管理服务。
            String id = target.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            caller.publisher().rsfService(Echo.class).register();
            target.start();
            caller.start();
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            assertSame(targets, caller.getExtension(TargetAdmin.class));
            assertNull(caller.getExtension(RegistryAdmin.class));
            assertNull(target.getExtension(RegistryAdmin.class));
            assertNull(caller.getExtension(RegistryClient.class));
            assertNull(target.getExtension(RegistryClient.class));
            assertNull(caller.bindAddress("rsf"));
            assertNotNull(caller.getServiceInfo(ManagementRPC.class));

            // 两侧均未配置存储，通过已知地址完成真实 TCP 管理调用。
            InterAddress listen = target.bindAddress("rsf");
            InterAddress address = new InterAddress("rsf", "127.0.0.1", listen.getPort(), listen.getFormUnit());
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(address);
            MetServiceAdmin service = targets.getServiceAdminByAddress(address, id);
            MetBindInfo<ManagementRPC> management = caller.getServiceInfo(ManagementRPC.class);
            assertEquals(MetServiceType.Provider, management.getServiceType());
            assertTrue(management.isShadow());
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());
            assertEquals(Map.of("rsf", address.toString()), instance.instance().getManagementAddress());
            assertEquals(1, instance.getServiceList(null).getItems().size());
            service.putMeta("owner", "direct");
            assertEquals("direct", target.getServiceInfo(Echo.class).getMetaData("owner"));

            // 阻断业务后管理 RPC 仍可解除阻断，调用方无需开启监听。
            instance.block();
            assertTrue(instance.isBlock());
            assertForbidden(() -> caller.getRsfClient(address).wrapper(Echo.class).echo("blocked"));
            instance.unblock();
            assertEquals("back", caller.getRsfClient(address).wrapper(Echo.class).echo("back"));
            service.disable();
            assertFalse(service.isEnable());
            service.enable();
            assertTrue(service.isEnable());

            // 关闭调用方释放代理所属上下文，不关闭远端目标。
            caller.close();
            assertNull(caller.getExtension(TargetAdmin.class));
            assertThrows(Throwable.class, instance::instance);
            assertTrue(target.isOnline());
        }
    }

    private static void assertRuleSwitchUnsupported(Runnable action) {
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, action::run);
        assertEquals("Rule enable/disable requires registry storage", failure.getMessage());
    }

    private static void assertForbidden(Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (!(failure instanceof MetException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue(failure instanceof MetException);
        assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());
    }
}
