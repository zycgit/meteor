/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos.scenario.storage;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.nacos.scenario.AbstractNacosExample;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import static org.junit.Assert.*;

/** 真实 Nacos 的业务存储示例；每次 main 只运行一个场景，保留持久档案供检查。 */
public class NacosStorageMain extends AbstractNacosExample {
    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "archives" : args[0];
        NacosStorageMain example = new NacosStorageMain();
        switch (scenario) {
            case "archives" -> example.archivesSurviveSessionClose();
            case "directories" -> example.registrationCanBeReadFromBothBusinessDirectories();
            case "group-isolation" -> example.groupsKeepSameServiceIdsIsolated();
            case "pagination" -> example.servicePagesDoNotOmitRecords();
            case "revision-conflict" -> example.metadataRejectsStaleRevision();
            case "recreation" -> example.recreatedMetadataRejectsDeletedRevision();
            case "foreign-session" -> example.foreignSessionCannotWithdrawMembership();
            case "withdrawal" -> example.subscriptionObservesProviderDeparture();
            case "runtime-governance" -> example.runtimeGovernanceFollowsApplicationLifetime();
            case "runtime-conflict" -> example.runtimeGovernanceRejectsStaleRevision();
            default -> throw new IllegalArgumentException("未知场景：" + scenario);
        }
        System.out.println("场景验证通过：" + scenario);
    }

    public void archivesSurviveSessionClose() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess reader = registry.openStorage()) {
            // 先登记在线实例与服务，确认另一个会话可读。
            try (StorageAccess writer = registry.openStorage()) {
                writer.registerInstance(instance());
                writer.registerService("node", service());
                await("Registration not visible", () -> reader.getInstanceService("node", "echo") != null);
            }

            // 关闭登记会话撤回实例和关系，应用与服务定义继续保留。
            await("Runtime remained online", () -> reader.getInstance("node") == null);
            assertNotNull(reader.getApplication("orders"));
            assertNull(reader.getInstance("node"));
            assertNotNull(reader.getService("echo"));
        }
    }

    public void registrationCanBeReadFromBothBusinessDirectories() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess writer = registry.openStorage(); //
             StorageAccess reader = registry.openStorage()) {
            // 一次服务登记，同时写入实例服务清单和服务提供者目录。
            writer.registerInstance(instance());
            writer.registerService("node", service());
            await("Provider not visible", () -> reader.getMember("echo", MetServiceType.Provider, "node") != null);

            // 两个业务入口都可直接取得对应的完整信息。
            RefServiceRelationData provider = reader.getMember("echo", MetServiceType.Provider, "node");
            EntServiceRuntimeData declaredService = reader.getInstanceService("node", "echo");
            assertEquals("orders", provider.getInstance().getApplicationName());
            assertEquals("echo", declaredService.getServiceId());
        }
    }

    public void groupsKeepSameServiceIdsIsolated() throws Exception {
        // 两个会话使用不同分组，其他连接参数保持一致。
        NacosRegistryConfiguration firstGroup = this.createRegistryConfiguration();
        NacosRegistryConfiguration secondGroup = this.createRegistryConfiguration();
        try (StorageAccess first = firstGroup.openStorage(); //
             StorageAccess second = secondGroup.openStorage()) {
            // 只在第一个分组登记服务，相同 ID 在第二个分组仍不存在。
            first.saveService(service());
            await("Service not visible", () -> first.getService("echo") != null);
            assertNull(second.getService("echo"));
        }
    }

    public void servicePagesDoNotOmitRecords() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess storage = registry.openStorage()) {
            // 准备超过两页的数据，最后一页不足指定页大小。
            for (int index = 0; index < 12; index++) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("service-" + index);
                storage.saveService(service);
            }

            // 连续读取游标页，检查无重复、无遗漏。
            DirectoryQuery query = new DirectoryQuery();
            query.setLimit(5);
            Set<String> ids = new HashSet<>();
            do {
                RecordPage<EntServiceCatalogData> page = storage.services(query);
                for (EntServiceCatalogData service : page.getItems()) {
                    boolean firstOccurrence = ids.add(service.getServiceId());
                    assertTrue(firstOccurrence);
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
            assertEquals(12, ids.size());
        }
    }

    public void metadataRejectsStaleRevision() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess storage = registry.openStorage()) {
            // 保存初始元信息，读取稍后会失效的版本。
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            storage.saveMetadata(null, "echo", initial);
            await("Metadata not visible", () -> storage.getMetadata(null, "echo") != null);
            EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "echo");

            // 另一次修改提交后，旧版本写入必须反馈冲突。
            EntityRecord<EntMetadataData> latest = storage.getMetadata(null, "echo");
            latest.getData().getMetadata().put("team", "orders");
            storage.saveMetadata(null, "echo", latest);
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "echo", previous));
        }
    }

    public void recreatedMetadataRejectsDeletedRevision() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess storage = registry.openStorage()) {
            // 保存初始元信息，读取稍后会失效的版本。
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            storage.saveMetadata(null, "echo", initial);
            await("Metadata not visible", () -> storage.getMetadata(null, "echo") != null);
            EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "echo");

            // 删除后重建相同键，删除前的版本不能覆盖新记录。
            storage.deleteMetadata(null, "echo", previous.getRevision());
            await("Deletion not visible", () -> storage.getMetadata(null, "echo") == null);
            storage.saveMetadata(null, "echo", initial);
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "echo", previous));
        }
    }

    public void foreignSessionCannotWithdrawMembership() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess writer = registry.openStorage(); //
             StorageAccess reader = registry.openStorage()) {
            // 原会话持有实例与服务登记，另一会话没有撤回权限。
            writer.registerInstance(instance());
            writer.registerService("node", service());

            // 错误暴露给调用者，原登记仍存在。
            assertThrows(IllegalStateException.class, () -> reader.unregisterService("node", "echo"));
            await("Provider not visible", () -> writer.getMember("echo", MetServiceType.Provider, "node") != null);
        }
    }

    public void subscriptionObservesProviderDeparture() throws Exception {
        // 通过正式配置打开本场景独占分组的存储会话。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess writer = registry.openStorage(); //
             StorageAccess reader = registry.openStorage()) {
            // 先订阅提供者变化，再登记一个可被观察到的成员。
            AtomicInteger events = new AtomicInteger();
            reader.watchProviders("echo", events::incrementAndGet);
            await("Initial snapshot not delivered", () -> events.get() > 0);
            writer.registerInstance(instance());
            writer.registerService("node", service());
            await("Provider not visible", () -> reader.getMember("echo", MetServiceType.Provider, "node") != null);

            // 先确认订阅已观察到上线，避免上线和下线被合并成同一份空快照。
            await("Arrival not notified", () -> events.get() > 1);

            // 关闭原会话后，订阅事件与成员消失共同证明退出已生效。
            int before = events.get();
            writer.close();
            await("Departure not notified", () -> {
                boolean departureNotified = events.get() > before;
                boolean providerRemoved = reader.getMember("echo", MetServiceType.Provider, "node") == null;
                return departureNotified && providerRemoved;
            });
        }
    }

    public void runtimeGovernanceFollowsApplicationLifetime() throws Exception {
        // 应用持有实例与服务临时节点，管理端使用独立连接下发交叉范围配置。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess application = registry.openStorage(); //
             StorageAccess reader = registry.openStorage()) {
            application.registerInstance(instance());
            application.registerService("node", service());
            await("Governance node not visible", () -> reader.getInstanceService("node", "echo") != null);
            try (StorageAccess admin = registry.openStorage()) {
                EntityRecord<EntMetadataData> value = new EntityRecord<>();
                value.setData(new EntMetadataData());
                value.getData().getMetadata().put("team", "orders");
                admin.saveMetadata("node", "echo", value);
            }

            // 管理端退出和应用重复上报均不改变治理节点的所有权或配置。
            application.registerInstance(instance());
            application.registerService("node", service());
            await("Governance not visible", () -> reader.getMetadata("node", "echo") != null);
            assertEquals("orders", reader.getMetadata("node", "echo").getData().getMetadata().get("team"));

            // 应用退出，交叉治理随原生临时登记消失，不留下 Config 记录。
            application.close();
            await("Governance remained after departure", () -> reader.getMetadata("node", "echo") == null);
            assertNull(reader.getInstance("node"));
            assertNotNull(reader.getService("echo"));
        }
    }

    public void runtimeGovernanceRejectsStaleRevision() throws Exception {
        // 在真实 Naming 节点上取得两个相同的治理版本。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        try (StorageAccess application = registry.openStorage(); //
             StorageAccess admin = registry.openStorage()) {
            application.registerInstance(instance());
            await("Instance not visible", () -> admin.getInstance("node") != null);
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            admin.saveMetadata("node", null, initial);
            await("Governance not visible", () -> admin.getMetadata("node", null) != null);
            EntityRecord<EntMetadataData> old = admin.getMetadata("node", null);
            EntityRecord<EntMetadataData> next = admin.getMetadata("node", null);

            // 新版本提交后，旧版本不能覆盖它，即使订阅缓存尚未收到通知。
            next.getData().getMetadata().put("team", "orders");
            admin.saveMetadata("node", null, next);
            assertThrows(ConcurrentModificationException.class, () -> admin.saveMetadata("node", null, old));
        }
    }

    private static EntInstanceRuntimeData instance() {
        EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
        instance.setInstanceId("node");
        instance.setApplicationName("orders");
        instance.setIpAddress("127.0.0.1");
        instance.setProcessId(ProcessHandle.current().pid());
        return instance;
    }

    private static EntServiceRuntimeData service() {
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId("echo");
        service.setName("Echo");
        service.setRole(MetServiceType.Provider);
        return service;
    }
}
