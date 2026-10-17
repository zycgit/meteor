/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceMember;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实 Meteor 调用验证存储接入、管理 RPC 生命周期和治理订阅。 */
public class RegistryAdminClientTest {
    @Test
    public void pushPublishesAllLocalInformationAndPullReappliesAllConfiguration() throws Exception {
        // 准备暂停订阅的扩展与记录观察会话，只验证主动推拉。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();

        // 暂停订阅，隔离主动推拉与后台刷新两个触发点。
        probe.setNotifications(false);
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        configuration.addExtension(registryConfig);
        configuration.setRequestOption("marker", "local");

        try (MetContext context = configuration.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 启动提供者和订阅者，并先应用注册中心的期望配置。
            MetBindInfo<Echo> provider = context.publisher().rsfService(Echo.class, value -> value).register();
            MetBindInfo<Echo> consumer = context.publisher().rsfService(Echo.class).name("consumer").register();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            String instanceId = context.getInstanceID();
            admin.getServiceAdmin(provider.getBindID()).putMeta("marker", "managed");
            admin.getServiceAdmin(consumer.getBindID()).putMeta("marker", "managed");
            client.pullSync().get(10, TimeUnit.SECONDS);

            // 推送只上报当前本地状态，不把本地偏差重新改回注册中心的期望值。
            provider.setMetaData("marker", "local");
            consumer.setMetaData("marker", "local");
            client.pushSync().get(10, TimeUnit.SECONDS);
            assertEquals("local", provider.getMetaData("marker"));
            assertEquals("local", consumer.getMetaData("marker"));

            // 读取上报记录，检查实例状态及发布、订阅关系。
            EntInstanceRuntimeData record = storage.getInstance(instanceId).getRuntime();
            assertEquals(context.getApplicationName(), record.getApplicationName());
            Map<?, ?> instanceBody = RegistryJson.copy(record, Map.class);
            assertFalse(instanceBody.containsKey("services"));
            assertFalse(instanceBody.containsKey("addressBooks"));
            assertFalse(instanceBody.containsKey("settings"));
            assertEquals(instanceId, record.getInstanceId());
            assertNotNull(storage.getMember(provider.getBindID(), MetServiceType.Provider, instanceId));
            assertNotNull(storage.getMember(consumer.getBindID(), MetServiceType.Consumer, instanceId));
            assertEquals(2, storage.instanceServices(instanceId, new DirectoryQuery()).getItems().size());

            // 两个读取方向均携带完整声明，但不存运行参数和地址诊断。
            EntServiceCatalogData declaration = storage.getService(provider.getBindID());
            Map<?, ?> catalog = RegistryJson.copy(declaration, Map.class);
            assertEquals(provider.getBindID(), catalog.get("serviceId"));
            assertFalse(catalog.containsKey("methods"));
            assertFalse(catalog.containsKey("timeout"));
            assertFalse(catalog.containsKey("serializeType"));
            assertEquals(provider.getBindName(), catalog.get("name"));

            // 管理查询从存储记录还原完整服务身份及成员信息。
            MetServiceAdmin serviceAdmin = admin.getServiceAdmin(provider.getBindID());
            assertEquals(provider.getBindID(), serviceAdmin.info().getServiceId());
            MetServiceMember member = serviceAdmin.providers(null, null).getItems().get(0);
            assertEquals(instanceId, member.getInstance().getInstanceId());
            assertEquals(provider.getBindID(), member.getService().getServiceId());
            assertEquals(1, member.getService().getProviderCount());
            assertEquals(0, member.getService().getConsumerCount());
            assertEquals(0, member.getEndpoints().length);
            assertEquals(instanceId, admin.getInstanceAdmin(instanceId).instance().getInstanceId());

            // 配置未变，拉取也必须覆盖全部服务与实例的偏差。
            client.pullSync().get(10, TimeUnit.SECONDS);
            assertEquals("managed", provider.getMetaData("marker"));
            assertEquals("managed", consumer.getMetaData("marker"));
            assertEquals("local", context.getSettings().getRequestOptions().getOption("marker"));
            assertNull(context.getListenAddress("rsf"));
        }
    }

    @Test
    public void scopedPushAndPullPreserveInstanceSettingsAndOtherServices() throws Exception {
        // 准备暂停订阅的扩展，单独控制每次推送和拉取。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();

        // 暂停订阅，隔离主动推拉与后台刷新两个触发点。
        probe.setNotifications(false);
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);
        clientConfiguration.setRequestOption("marker", "local");

        try (MetContext context = clientConfiguration.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 启动两个服务，并通过全量拉取建立一致的初始状态。
            MetBindInfo<Echo> first = context.publisher().rsfService(Echo.class, value -> value).register();
            MetBindInfo<Echo> other = context.publisher().rsfService(Echo.class).name("other").register();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            String instanceId = context.getInstanceID();
            admin.getServiceAdmin(first.getBindID()).putMeta("marker", "managed");
            admin.getServiceAdmin(other.getBindID()).putMeta("marker", "managed");
            client.pullSync().get(10, TimeUnit.SECONDS);

            // 保存实例及另一个服务的记录版本，用于检查单服务操作的边界。
            String instanceSnapshot = RegistryJson.write(storage.getInstance(instanceId));
            String otherId = other.getBindID();
            String otherSnapshot = RegistryJson.write(storage.getInstanceService(instanceId, otherId));

            // 单服务推送不回写实例记录，也不删除另一个服务的成员关系。
            first.setMetaData("marker", "local");
            other.setMetaData("marker", "local");
            client.pushSync(first.getBindID()).get(10, TimeUnit.SECONDS);
            assertEquals("local", first.getMetaData("marker"));
            EntServiceRuntimeData afterPush = storage.getInstanceService(instanceId, otherId);
            assertNotNull(afterPush);
            assertEquals(instanceSnapshot, RegistryJson.write(storage.getInstance(instanceId)));
            assertEquals(otherSnapshot, RegistryJson.write(afterPush));

            // 单服务拉取只修复选中的服务，另一个服务和实例设置保留本地值。
            client.pullSync(first.getBindID()).get(10, TimeUnit.SECONDS);
            assertEquals("managed", first.getMetaData("marker"));
            assertEquals("local", other.getMetaData("marker"));
            assertEquals("local", context.getSettings().getRequestOptions().getOption("marker"));
            assertEquals(instanceSnapshot, RegistryJson.write(storage.getInstance(instanceId)));
            EntServiceRuntimeData afterPull = storage.getInstanceService(instanceId, otherId);
            assertEquals(otherSnapshot, RegistryJson.write(afterPull));
            client.pullSync(other.getBindID()).get(10, TimeUnit.SECONDS);
            assertEquals("managed", other.getMetaData("marker"));

            // 空服务标识立即报错，不存在的服务通过 Future 报错。
            assertThrows(IllegalArgumentException.class, () -> client.pushSync(null));
            assertThrows(IllegalArgumentException.class, () -> client.pullSync(" "));
            assertThrows(ExecutionException.class, () -> client.pushSync("missing").get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.pullSync("missing").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void pushAndPullReportLifecycleAndStorageFailures() throws Exception {
        // 准备可切换存储故障的客户端扩展。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        try (MetContext context = clientConfiguration.buildContext()) {
            // 正常启动后，全量推拉均可完成。
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            client.pushSync().get(5, TimeUnit.SECONDS);
            client.pullSync().get(5, TimeUnit.SECONDS);

            // 存储不可用时主动推拉必须通过 Future 报错。
            probe.setUnavailable(true);
            try {
                assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> client.pullSync().get(5, TimeUnit.SECONDS));
            } finally {
                probe.setUnavailable(false);
            }

            // 恢复存储后推拉可继续；上下文关闭后再次拒绝操作。
            client.pushSync().get(5, TimeUnit.SECONDS);
            client.pullSync().get(5, TimeUnit.SECONDS);
            context.close();
            assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.pullSync().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void createsBeforeStartAndDiscoversLateBusinessServices() throws Exception {
        // 准备共享注册中心的服务端与调用方配置。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfig);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext caller = clientConfiguration.buildContext()) {
            // 构建上下文只安装扩展，启动前没有存储会话或发布地址。
            RegistryClient provider = server.getExtension(RegistryClient.class);
            RegistryClient consumer = caller.getExtension(RegistryClient.class);
            assertEquals(0, probe.openedSessions());
            assertFalse(RegistryTestUtils.hasRegistrations(storageConfiguration));
            MetBindInfo<Echo> binding = caller.publisher().rsfService(Echo.class).register();
            assertFalse(RegistryTestUtils.hasPublishedAddress(storageConfiguration));

            // 启动后再发布服务，调用方通过订阅发现地址并完成 RPC。
            server.start();
            caller.start();
            server.publisher().rsfService(Echo.class, value -> "reply:" + value).register();
            RegistryTestUtils.await(() -> !caller.getUpdater().queryAvailableAddresses(binding.getBindID()).isEmpty());
            assertEquals("reply:hello", caller.getRsfClient().wrapper(Echo.class).echo("hello"));
            assertEquals(2, server.getServiceIDs().size());
            assertEquals(2, caller.getServiceIDs().size());
            assertTrue(server.getServiceInfo(ManagementRPC.class).isShadow());
            assertTrue(caller.getServiceInfo(ManagementRPC.class).isShadow());
            assertNull(caller.getListenAddress("rsf"));

            // 等待两端上报，使用独立管理上下文检查监听与公告地址。
            provider.pushSync().get(5, TimeUnit.SECONDS);
            consumer.pushSync().get(5, TimeUnit.SECONDS);
            MetConfiguration adminConfiguration = new MetConfiguration().addExtension(registryConfig);

            try (MetContext adminContext = adminConfiguration.buildContext()) {
                RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
                MetInstanceAdmin serverAdmin = admin.getInstanceAdmin(server.getInstanceID());
                Map<String, String> management = serverAdmin.instance().getManagementAddress();
                assertEquals(Map.of("tcp-0#rsf", server.getListenAddress("rsf").toString()), management);
                MetInstanceAdmin callerAdmin = admin.getInstanceAdmin(caller.getInstanceID());
                assertTrue(callerAdmin.instance().getManagementAddress().isEmpty());
                MetServiceAdmin service = admin.getServiceAdmin(binding.getBindID());
                MetServiceMember member = service.providers(null, null).getItems().get(0);
                assertEquals("rsf", member.getEndpoints()[0].getProtocol());
                assertEquals(server.getListenAddress("rsf").toString(), member.getEndpoints()[0].getPublishedAddress());
                assertArrayEquals(new String[] { server.getListenAddress("rsf").toString() }, member.getAddresses());
            }

            // 服务端下线、上线和关闭分别触发地址撤销、恢复和最终删除。
            server.offline();
            RegistryTestUtils.await(() -> caller.getUpdater().queryAvailableAddresses(binding.getBindID()).isEmpty());
            server.online();
            RegistryTestUtils.await(() -> !caller.getUpdater().queryAvailableAddresses(binding.getBindID()).isEmpty());
            server.close();
            assertFalse(server.isOnline());
            RegistryTestUtils.await(() -> caller.getUpdater().queryAvailableAddresses(binding.getBindID()).isEmpty());
        }

        // 所有上下文退出后，临时注册与存储会话均被清理。
        assertFalse(RegistryTestUtils.hasRegistrations(storageConfiguration));
        assertEquals(probe.openedSessions(), probe.closedSessions());
    }

    @Test
    public void storageChangesApplyGovernanceAndRetainStateOnFailure() throws Exception {
        // 准备支持订阅通知和故障注入的注册中心及两个应用。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfig);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        serverConfiguration.setRequestOption("baseline", "local");

        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext caller = clientConfiguration.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);

            // 启动服务与调用方，取得存储管理入口。
            MetBindInfo<Echo> binding = server.publisher().rsfService(Echo.class, value -> value).register();
            caller.publisher().rsfService(Echo.class).register();
            server.start();
            caller.start();
            RegistryClient provider = server.getExtension(RegistryClient.class);
            RegistryClient consumer = caller.getExtension(RegistryClient.class);
            String id = binding.getBindID();
            MetServiceAdmin service = manager.getServiceAdmin(id);
            MetInstanceAdmin instance = manager.getInstanceAdmin(server.getInstanceID());

            // 元信息新增与删除通过订阅应用到服务。
            service.putMeta("owner", "ops");
            RegistryTestUtils.await(() -> ObjectUtils.equals("ops", binding.getMetaData("owner")));
            service.removeMeta("owner");
            RegistryTestUtils.await(() -> binding.getMetaData("owner") == null);

            // 注册中心治理不覆盖应用本地配置。
            assertEquals("local", server.getSettings().getRequestOptions().getOption("baseline"));

            // 阻断实例的入站业务 RPC，本地调用仍可执行。
            instance.block();
            RegistryTestUtils.await(() -> instance.instance().isBlock());
            assertTrue(server.isOnline());
            Echo remote = caller.getRsfClient(server.getListenAddress("rsf")).wrapper(Echo.class);
            Throwable rejected = assertThrows(Throwable.class, () -> remote.echo("blocked"));
            while (!(rejected instanceof MetException) && rejected.getCause() != null) {
                rejected = rejected.getCause();
            }
            assertTrue(rejected instanceof MetException);
            assertEquals(ProtocolStatus.Forbidden, ((MetException) rejected).getStatus());
            assertEquals("local", server.getRsfClient().wrapper(Echo.class).echo("local"));

            // 删除实例配置解除阻断；应用运行选项不受注册中心影响。
            String ref = server.getInstanceID();
            EntityRecord<EntConfigurationData> state = storage.getConfiguration(ref, null);
            if (state != null) {
                storage.deleteConfiguration(ref, null, state.getRevision());
            }
            RegistryTestUtils.await(() -> !instance.instance().isBlock());
            assertEquals("local", server.getSettings().getRequestOptions().getOption("baseline"));
            MetInstanceAdmin callerAdmin = manager.getInstanceAdmin(caller.getInstanceID());

            // 等待提供者应用启停配置、消费者更新本地地址本，再进入下一步。
            service.disable();
            RegistryTestUtils.await(() -> {
                List<MetServiceMember> providers = service.providers(null, null).getItems();
                boolean disabled = providers.size() == 1 && !providers.get(0).isEnabled();
                boolean empty = caller.getUpdater().queryAvailableAddresses(id).size() == 0;
                return disabled && empty;
            });
            service.enable();
            RegistryTestUtils.await(() -> {
                List<MetServiceMember> providers = service.providers(null, null).getItems();
                boolean enabled = providers.size() == 1 && providers.get(0).isEnabled();
                boolean available = caller.getUpdater().queryAvailableAddresses(id).size() > 0;
                return enabled && available;
            });
            assertEquals("back", caller.getRsfClient().wrapper(Echo.class).echo("back"));

            // 阻断调用方时拒绝出站业务 RPC，解除后继续治理操作。
            callerAdmin.block();
            RegistryTestUtils.await(() -> callerAdmin.instance().isBlock());
            Throwable outgoing = assertThrows(Throwable.class, () -> remote.echo("blocked-outgoing"));
            while (!(outgoing instanceof MetException) && outgoing.getCause() != null) {
                outgoing = outgoing.getCause();
            }
            assertTrue(outgoing instanceof MetException);
            assertEquals(ProtocolStatus.Forbidden, ((MetException) outgoing).getStatus());
            callerAdmin.unblock();
            RegistryTestUtils.await(() -> !callerAdmin.instance().isBlock());

            // 无效存储配置通过主动拉取报错，保留应用已有有效配置。
            EntityRecord<EntRuleConfigurationData> invalid = new EntityRecord<>();
            EntRuleConfigurationData invalidData = new EntRuleConfigurationData();
            invalidData.setRule("<route><unknown/></route>");
            invalid.setData(invalidData);
            storage.saveRule(null, id, RuleKind.SERVICE_ROUTE, invalid);
            Future<Void> invalidPull = provider.pullSync();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> invalidPull.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("local", server.getSettings().getRequestOptions().getOption("baseline"));

            storage.deleteRule(null, id, RuleKind.SERVICE_ROUTE, storage.getRule(null, id, RuleKind.SERVICE_ROUTE).getRevision());

            // 存储正文解析失败也必须通过主动拉取的 Future 传播，保留本地有效配置。
            probe.setReadFailure(() -> new IOException("Malformed stored record"));
            Future<Void> malformedPull = provider.pullSync();
            ExecutionException parseFailure = assertThrows(ExecutionException.class, () -> {
                malformedPull.get(5, TimeUnit.SECONDS);
            });
            assertTrue(parseFailure.getCause() instanceof IOException);
            assertEquals("Malformed stored record", parseFailure.getCause().getMessage());
            probe.setReadFailure(null);
            assertEquals("local", server.getSettings().getRequestOptions().getOption("baseline"));
            instance.unblock();
            provider.pullSync().get(5, TimeUnit.SECONDS);

            // 无变更时上报记录不持续写入，避免自身状态通知形成反馈循环。
            Thread.sleep(1300);
            String stable = RegistryJson.write(storage.getInstance(ref));
            Thread.sleep(1300);
            assertEquals(stable, RegistryJson.write(storage.getInstance(ref)));
        }
    }

    @Test
    public void outageRetainsAddressesAndRecoveryRebuildsLostRegistration() throws Exception {
        // 准备自动发现服务的两端与可模拟失联的注册中心。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "Json");
        serverConfiguration.addExtension(registryConfig);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext caller = clientConfiguration.buildContext()) {
            // 启动并等待地址发现完成，作为存储故障前的基准。
            String id = server.publisher().rsfService(Echo.class, value -> value).register().getBindID();
            caller.publisher().rsfService(Echo.class).register();
            server.start();
            caller.start();
            RegistryClient provider = server.getExtension(RegistryClient.class);
            RegistryClient consumer = caller.getExtension(RegistryClient.class);
            RegistryTestUtils.await(() -> !caller.getUpdater().queryAvailableAddresses(id).isEmpty());

            // 存储失联后，已发现的地址继续支持业务调用。
            probe.setUnavailable(true);

            // 让轮询经历一次失败；失败不能解释为提供者列表为空。
            Thread.sleep(1300);
            assertFalse(caller.getUpdater().queryAvailableAddresses(id).isEmpty());
            assertEquals("cached", caller.getRsfClient().wrapper(Echo.class).echo("cached"));

            // 模拟会话丢失并恢复存储，客户端应重新登记并恢复发现。
            probe.withdrawSessionServices();
            probe.setUnavailable(false);
            RegistryTestUtils.await(() -> RegistryTestUtils.hasPublishedAddress(storageConfiguration));
            RegistryTestUtils.await(() -> !caller.getUpdater().queryAvailableAddresses(id).isEmpty());
            assertEquals("recovered", caller.getRsfClient().wrapper(Echo.class).echo("recovered"));
        } finally {
            probe.setUnavailable(false);
        }
    }

    @Test
    public void contextCloseReleasesSessionAndStartupFailureClosesContext() throws Exception {
        // 准备可重复构建的扩展配置和会话观察器。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        try (MetContext context = clientConfiguration.buildContext()) {
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 场景一：启动前拒绝推拉，启动再关闭后释放会话及注册记录。
            assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.pullSync().get(5, TimeUnit.SECONDS));
            context.start();
            context.close();
            assertEquals(probe.openedSessions(), probe.closedSessions());
            assertFalse(RegistryTestUtils.hasRegistrations(storageConfiguration));
            assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
        }

        try (MetContext context = clientConfiguration.buildContext()) {
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 场景二：启动时存储故障，上下文清理后不得重新启动或拉取。
            probe.setUnavailable(true);
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertEquals("offline storage", failure.getCause().getMessage());
            assertFalse(context.isOnline());
            assertThrows(IllegalStateException.class, context::start);
            assertEquals(probe.openedSessions(), probe.closedSessions());
            Future<Void> pull = client.pullSync();
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> pull.get(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof IllegalStateException);
        } finally {
            probe.setUnavailable(false);
        }
        assertEquals(probe.openedSessions(), probe.closedSessions());
    }

    @Test
    public void closeBeforeStartDoesNotOpenStorage() throws Exception {
        // 准备在线扩展，但不启动应用。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);

        try (MetContext context = clientConfiguration.buildContext()) {
            // 构建阶段已发布管理服务，关闭时不应额外打开存储。
            RegistryClient client = context.getExtension(RegistryClient.class);
            assertNotNull(context.getServiceInfo(ManagementRPC.class));
            context.close();

            // 关闭后的启动和拉取被拒绝，存储会话数保持为零。
            assertThrows(IllegalStateException.class, context::start);
            assertThrows(ExecutionException.class, () -> client.pullSync().get(5, TimeUnit.SECONDS));
            assertEquals(0, probe.openedSessions());
        }
    }

    @Test
    public void invalidClientConfigurationAndDuplicateExtensionsFailWithoutStorage() {
        // 准备共享配置与存储观察器。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");
        clientConfiguration.addExtension(registryConfig);
        MetConfiguration baseConfiguration = new MetConfiguration();
        baseConfiguration.connector("tcp").protocol("rsf").option("serializeType", "Json");

        // 场景一：客户端刷新间隔无效，在构建阶段失败。
        RegistryConfiguration invalid = registryConfig.setRefreshMillis(0);
        MetConfiguration invalidApplication = baseConfiguration;
        invalidApplication.addExtension(invalid);
        assertThrows(IllegalStateException.class, invalidApplication::buildContext);

        // 场景二：恢复有效配置后重复安装，仍在打开存储前失败。
        registryConfig.setRefreshMillis(60000);
        MetConfiguration duplicate = clientConfiguration;
        duplicate.addExtension(registryConfig);
        assertThrows(IllegalStateException.class, duplicate::buildContext);
        assertEquals(0, probe.openedSessions());
    }

    @Test
    public void wildcardPublicationRequiresPublishHostAndClosesFailedContext() throws Exception {
        // 准备扩展配置与存储会话观察器。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(60000);

        // 场景一：监听通配地址却未指定发布主机，启动失败并清理资源。
        MetConfiguration missing = new MetConfiguration();
        missing.connector("tcp").bind("0.0.0.0", 0).protocol("rsf");
        missing.addExtension(registryConfig);

        try (MetContext context = missing.buildContext()) {
            RegistryClient client = context.getExtension(RegistryClient.class);
            context.publisher().rsfService(Echo.class, value -> value).register();
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertFalse(context.isOnline());
            assertNull(context.getListenAddress("rsf"));
            assertFalse(RegistryTestUtils.hasRegistrations(storageConfiguration));
            assertEquals(probe.openedSessions(), probe.closedSessions());
            Future<Void> push = client.pushSync();
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> push.get(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof IllegalStateException);
        }

        // 场景二：补充发布主机后，能正常注册可用地址。
        MetConfiguration published = new MetConfiguration();
        published.connector("tcp").bind("0.0.0.0", 0).protocol("rsf");
        published.addExtension(registryConfig.setPublishHost("127.0.0.1"));

        try (MetContext context = published.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).register();
            context.start();
            assertTrue(RegistryTestUtils.hasPublishedAddress(storageConfiguration));
        }

        // 成功场景退出后也应清理临时注册和全部会话。
        assertFalse(RegistryTestUtils.hasRegistrations(storageConfiguration));
        assertEquals(probe.openedSessions(), probe.closedSessions());
    }

}
