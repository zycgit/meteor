/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GovernanceSubscriptionTest {
    @MetService
    public interface Other {
        String call();
    }

    @Test
    public void serviceAndInstanceChangesLeaveUnrelatedRuntimeRecordsUntouched() throws Exception {
        // 通过具体存储配置接入扩展；两个应用各自创建独立客户端和存储会话。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.setSerializeType("Json");
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        // 管理端使用同一配置；独立存储会话仅用于检查记录版本。
        try (MetContext a = application.buildContext(); //
             MetContext b = application.buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            RegistryAdmin admin = a.getExtension(RegistryAdmin.class);
            MetBindInfo<Echo> echo = a.publisher().rsfService(Echo.class).register();
            MetBindInfo<Other> other = b.publisher().rsfService(Other.class).register();
            a.start();
            b.start();

            // 单个服务的变更不能改写另一个实例的运行记录。
            EntityRef otherInstance = RegistryGovernanceUtils.instance(b.getInstanceID());
            EntityRef otherRuntime = RegistryGovernanceUtils.runtime(otherInstance);
            String otherRevision = storage.getEntity(otherRuntime).getRevision();
            admin.getServiceAdmin(echo.getBindID()).putMeta("owner", "first");
            RegistryTestUtils.await(() -> ObjectUtils.equals("first", echo.getMetaData("owner")));
            assertEquals("first", echo.getMetaData("owner"));
            assertNull(other.getMetaData("owner"));
            assertEquals(otherRevision, storage.getEntity(otherRuntime).getRevision());

            // 实例治理只影响指定实例。
            MetInstanceAdmin firstInstance = admin.getInstanceAdmin(a.getInstanceID());
            MetInstanceAdmin secondInstance = admin.getInstanceAdmin(b.getInstanceID());
            firstInstance.block();
            RegistryTestUtils.await(() -> firstInstance.instance().isBlock());
            assertTrue(firstInstance.instance().isBlock());
            assertFalse(secondInstance.instance().isBlock());

            // 全局治理则应同步应用到两个实例的服务。
            admin.getClusterAdmin().putMeta("global", "all");
            RegistryTestUtils.await(() -> {
                boolean firstApplied = ObjectUtils.equals("all", echo.getMetaData("global"));
                boolean secondApplied = ObjectUtils.equals("all", other.getMetaData("global"));
                return firstApplied && secondApplied;
            });
        }
    }

    @Test
    public void configurationAndRulesSurviveSessionWhileCurrentServicesAndRelationsDisappear() throws Exception {
        // 准备应用、独立管理上下文与记录观察会话。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registryConfig = storageConfiguration;
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.setSerializeType("Json");
        application.connector("tcp").protocol("rsf");
        application.addExtension(registryConfig);

        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(registryConfig);

        try (MetContext context = application.buildContext(); //
             MetContext adminContext = adminConfiguration.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 启动服务后分别写入实例选项和实例内服务规则。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            String instanceId = context.getInstanceID();
            MetInstanceAdmin instance = admin.getInstanceAdmin(instanceId);
            instance.setRequestOption("request", "managed");
            MetServiceAdmin local = admin.getServiceAdmin(service.getBindID(), instanceId);
            local.updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
            client.pullSync().get(5, TimeUnit.SECONDS);

            // 拉取和推送不应改写持久规则记录的版本。
            EntityRef child = RegistryGovernanceUtils.localService(instanceId, service.getBindID());
            EntityRecord rule = storage.getEntity(RegistryGovernanceUtils.ruleRef(child, RuleTypeData.FLOW_CONTROL));
            assertNotNull(rule);
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertEquals(rule.getRevision(), storage.getEntity(rule.getRef()).getRevision());

            // 关闭应用，运行记录和成员关系退出，配置及规则仍保留。
            context.close();
            assertNull(storage.getEntity(RegistryGovernanceUtils.runtime(child)));
            assertNotNull(storage.getEntity(RegistryGovernanceUtils.service(service.getBindID())));
            assertNotNull(storage.getEntity(rule.getRef()));
            assertNotNull(storage.getEntity(RegistryGovernanceUtils.instance(instanceId)));
            RelationQuery query = new RelationQuery();
            query.setTarget(RegistryGovernanceUtils.instance(instanceId));
            assertTrue(storage.findRelations(query).records().isEmpty());
            assertEquals(1, admin.getClusterAdmin().services(null).length);

            // 服务目录继续保留服务身份，即使已没有在线成员。
            MetServiceAdmin catalog = admin.getServiceAdmin(service.getBindID());
            assertEquals(service.getBindID(), catalog.info().getServiceId());
            assertEquals(service.getBindName(), catalog.info().getName());
            assertEquals(service.getBindGroup(), catalog.info().getGroup());
            assertEquals(service.getBindVersion(), catalog.info().getVersion());
        }
    }

    @Test
    public void instanceRulesAndLocalServiceRulesUseExplicitOwnershipAndPriority() throws Exception {
        // 准备依赖存储通知更新的应用，延长周期核对间隔。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registryConfig = storageConfiguration;
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.setSerializeType("Json");
        application.connector("tcp").protocol("rsf");
        application.addExtension(registryConfig);

        try (MetContext context = application.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 启动服务并取得全局、服务、实例及实例内服务四级管理入口。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.start();
            String id = context.getInstanceID();
            String serviceId = service.getBindID();
            String global = "def evalAddress(id, addresses) { addresses }";
            String serviceRule = "def evalAddress(id, addresses) { [] }";
            String instanceRule = "def evalAddress(id, addresses) { return addresses }";
            String localRule = "def evalAddress(id, addresses) { return [] }";
            MetClusterAdmin all = admin.getClusterAdmin();
            MetServiceAdmin svc = admin.getServiceAdmin(service.getBindID());
            MetInstanceAdmin instance = admin.getInstanceAdmin(id);
            MetServiceAdmin local = admin.getServiceAdmin(service.getBindID(), id);

            // 延长轮询间隔，逐层验证独立规则记录的订阅与覆盖顺序。
            all.updateRule(MetRuleType.SERVICE_ROUTE, global);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(global, currentRule);
            });

            // 服务规则覆盖全局规则。
            svc.updateRule(MetRuleType.SERVICE_ROUTE, serviceRule);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(serviceRule, currentRule);
            });

            // 实例规则覆盖该实例上服务的规则。
            instance.updateRule(MetRuleType.SERVICE_ROUTE, instanceRule);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(instanceRule, currentRule);
            });

            // 实例内服务规则拥有最高优先级。
            local.updateRule(MetRuleType.SERVICE_ROUTE, localRule);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(localRule, currentRule);
            });

            // 禁用后停止生效且不继承上层；启用恢复，重置才回退上层。
            local.disableRule(MetRuleType.SERVICE_ROUTE);
            RegistryTestUtils.await(() -> context.getUpdater().serviceRoute(serviceId) == null);
            local.enableRule(MetRuleType.SERVICE_ROUTE);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(localRule, currentRule);
            });
            local.resetRule(MetRuleType.SERVICE_ROUTE);
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(instanceRule, currentRule);
            });

            // 实例内服务元信息也由独立记录通知更新。
            local.putMeta("scope", "local");
            RegistryTestUtils.await(() -> ObjectUtils.equals("local", service.getMetaData("scope")));

            // 删除规则也直接触发更新，不需要改写父对象。
            EntityRef owner = RegistryGovernanceUtils.instance(id);
            EntityRef rule = RegistryGovernanceUtils.ruleRef(owner, RuleTypeData.SERVICE_ROUTE);
            storage.deleteEntity(storage.getEntity(rule));
            RegistryTestUtils.await(() -> {
                String currentRule = context.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(serviceRule, currentRule);
            });
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.RULE);
            query.setParent(RegistryGovernanceUtils.instance(id));
            assertTrue(storage.findEntities(query).records().isEmpty());
        }
    }

    @Test
    public void partialPullLeavesOtherServicesAndInstanceSettingsUntouched() throws Exception {
        // 暂停订阅通知，只观察主动拉取的作用范围。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registryConfig = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registryConfig.setManagementToken("secret");
        registryConfig.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.setSerializeType("Json");
        application.connector("tcp").protocol("rsf");
        application.addExtension(registryConfig);

        try (MetContext context = application.buildContext(); //
             StorageAccess storage = storageConfiguration.openStorage()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 启动两个服务，并准备全局元信息和实例选项变更。
            MetBindInfo<Echo> first = context.publisher().rsfService(Echo.class).register();
            MetBindInfo<Other> other = context.publisher().rsfService(Other.class).register();
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            admin.getClusterAdmin().putMeta("global", "new");
            admin.getInstanceAdmin(context.getInstanceID()).setRequestOption("scope", "new");

            // 只拉取第一个服务，不应用另一个服务或实例设置。
            client.pullSync(first.getBindID()).get(5, TimeUnit.SECONDS);
            assertEquals("new", first.getMetaData("global"));
            assertNull(other.getMetaData("global"));
            assertNull(context.getSettings().getRequestOptions().getOption("scope"));
            EntityRef instance = RegistryGovernanceUtils.instance(context.getInstanceID());
            EntityRef identity = RegistryGovernanceUtils.runtime(instance);
            EntInstanceRuntimeData partial = ((EntInstanceRuntimeData) storage.getEntity(identity).getData());
            assertFalse(((Map<?, ?>) partial.getSettings().get("requestOptions")).containsKey("scope"));

            // 全量拉取才应用其余服务和实例设置，并更新上报快照。
            client.pullSync().get(5, TimeUnit.SECONDS);
            assertEquals("new", other.getMetaData("global"));
            assertEquals("new", context.getSettings().getRequestOptions().getOption("scope"));
            EntInstanceRuntimeData all = ((EntInstanceRuntimeData) storage.getEntity(identity).getData());
            assertEquals("new", ((Map<?, ?>) all.getSettings().get("requestOptions")).get("scope"));

            // 存储模型只使用约定的实体和关系类型。
            Set<EntityType> entityTypes = Set.of(EntityType.GLOBAL, EntityType.INSTANCE, EntityType.SERVICE, EntityType.RULE);
            assertEquals(entityTypes, Set.of(EntityType.values()));
            assertEquals(Set.of(RelationType.PROVIDES, RelationType.CONSUMES), Set.of(RelationType.values()));
        }
    }

}
