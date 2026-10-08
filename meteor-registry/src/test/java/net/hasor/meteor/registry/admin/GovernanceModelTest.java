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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.rpc.ConfigurationData;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntData;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class GovernanceModelTest {
    private final MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();

    @Before
    public void configure() {
        this.storageConfiguration.setManagementToken("secret");
    }

    @Test
    public void ruleUpdatesWriteOnlyTheirOwnRecord() throws Exception {
        // 准备管理扩展与用于检查记录版本的存储会话。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.storageConfiguration);

        try (StorageAccess storage = this.storageConfiguration.openStorage(); //
             MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 为两个服务分别创建基础记录，并保存更新前版本。
            MetServiceAdmin first = admin.getServiceAdmin("first");
            MetServiceAdmin second = admin.getServiceAdmin("second");
            first.putMeta("key", "value");
            second.putMeta("key", "value");
            EntityRef owner = RegistryGovernanceUtils.service("first");
            String firstRevision = storage.getEntity(owner).getRevision();
            EntityRef other = RegistryGovernanceUtils.service("second");
            String otherRevision = storage.getEntity(other).getRevision();

            // 更新规则只创建所属规则记录，不改写服务及全局对象。
            first.updateRule(MetRuleType.SERVICE_ROUTE, "def evalAddress(id, addresses) { addresses }");
            EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, RuleTypeData.SERVICE_ROUTE);
            assertEquals(owner, storage.getEntity(ref).getRef().parent());
            assertEquals(firstRevision, storage.getEntity(owner).getRevision());
            assertEquals(otherRevision, storage.getEntity(other).getRevision());
            assertNull(storage.getEntity(RegistryGovernanceUtils.GLOBAL));

            // 禁用保留正文，重置则清空当前规则。
            first.disableRule(MetRuleType.SERVICE_ROUTE);
            EntRuleConfigurationData rule = ((EntRuleConfigurationData) storage.getEntity(ref).getData());
            assertFalse(rule.isEnabled());
            assertNotNull(rule.getRule());
            first.resetRule(MetRuleType.SERVICE_ROUTE);
            assertNull(first.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertFalse(first.isRule(MetRuleType.SERVICE_ROUTE));

            // 全局元信息独立保存，也不改写已有服务记录。
            admin.getClusterAdmin().putMeta("global", true);
            ConfigurationData global = RegistryGovernanceUtils.read(storage, RegistryGovernanceUtils.GLOBAL);
            assertEquals(Boolean.TRUE, global.getMetadata().get("global"));
            EntData globalContent = storage.getEntity(RegistryGovernanceUtils.GLOBAL).getData();
            assertEquals(Set.of("metadata"), RegistryJson.copy(globalContent, Map.class).keySet());
            assertEquals(firstRevision, storage.getEntity(owner).getRevision());
            assertEquals(otherRevision, storage.getEntity(other).getRevision());
        }
    }

    @Test
    public void concurrentMetadataUpdatesMergeWithoutLostValues() throws Exception {
        // 准备共享服务管理入口和独立的记录观察会话。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.storageConfiguration);

        try (StorageAccess storage = this.storageConfiguration.openStorage(); //
             MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
            ExecutorService worker = Executors.newFixedThreadPool(4);
            try {
                // 并发提交不同元信息键的更新。
                List<Future<?>> pending = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    int key = i;
                    pending.add(worker.submit(() -> admin.getServiceAdmin("service").putMeta("key" + key, key)));
                }

                // 等待所有更新完成，再验证每个键都被保留。
                for (Future<?> result : pending) {
                    result.get(5, TimeUnit.SECONDS);
                }
                EntityRef service = RegistryGovernanceUtils.service("service");
                ConfigurationData data = RegistryGovernanceUtils.read(storage, service);
                assertEquals(4, data.getMetadata().size());
                for (int i = 0; i < 4; i++) {
                    assertEquals(i, data.getMetadata().get("key" + i));
                }
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    public void storageExceptionsRemainVisibleToManagementCallers() throws Exception {
        // 模拟存储操作报表不存在，关闭仍然允许执行。
        SQLException original = new SQLException("Table meteor_records does not exist", "42S02");
        DecoratedRegistryConfiguration failing = new DecoratedRegistryConfiguration(this.storageConfiguration, storage -> {
            return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(),
                    new Class<?>[] { StorageAccess.class }, (proxy, method, args) -> {
                if (StringUtils.equals(method.getName(), "close")) {
                    storage.close();
                    return null;
                }
                throw original;
            });
        });

        // 通过扩展创建管理入口，故障在实际操作时触发。
        failing.setManagementToken("secret");
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(failing);

        try (MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 查询、更新和规则读取都应保留原始存储异常。
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            assertSame(original, assertThrows(IllegalStateException.class, service::info).getCause());
            assertSame(original, assertThrows(IllegalStateException.class, service::enable).getCause());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertSame(original, failure.getCause());

            // 状态查询将同一故障转为可展示的错误信息。
            assertTrue(admin.getClusterAdmin().status().getError().contains("meteor_records"));
        }
    }

    @Test
    public void metadataLimitAndInvalidRuleDoNotCommit() throws Exception {
        // 准备管理入口与记录版本观察会话。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.storageConfiguration);

        try (StorageAccess storage = this.storageConfiguration.openStorage(); //
             MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin("echo");

            // 先填满允许的元信息容量，并记录当前版本。
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                values.put("key" + i, i);
            }
            service.putMeta(values);
            EntityRef ref = RegistryGovernanceUtils.service("echo");
            String revision = storage.getEntity(ref).getRevision();

            // 超限元信息和无效规则均不能提交存储。
            assertThrows(IllegalArgumentException.class, () -> service.putMeta("overflow", true));
            assertThrows(IllegalArgumentException.class, () -> {
                service.updateRule(MetRuleType.FLOW_CONTROL, "<invalid/>");
            });
            assertEquals(revision, storage.getEntity(ref).getRevision());
            assertNull(storage.getEntity(RegistryGovernanceUtils.ruleRef(ref, RuleTypeData.FLOW_CONTROL)));

            // 批量删除只移除指定的两个键。
            service.removeMeta("key0", "key1");
            ConfigurationData remaining = RegistryGovernanceUtils.read(storage, ref);
            assertEquals(998, remaining.getMetadata().size());
        }
    }

    @Test
    public void failedRuleWriteReportsTheStorageErrorWithoutChangingOtherRecords() throws Exception {
        // 准备只让目标规则首次写入失败的存储观察器。
        try (StorageAccess storage = this.storageConfiguration.openStorage()) {
            EntityRef owner = RegistryGovernanceUtils.service("echo");
            EntityRef rule = RegistryGovernanceUtils.ruleRef(owner, RuleTypeData.FLOW_CONTROL);
            AtomicBoolean failWrite = new AtomicBoolean();
            SQLException original = new SQLException("Cannot write governance rule");
            DecoratedRegistryConfiguration observed = new DecoratedRegistryConfiguration(this.storageConfiguration, delegate -> {
                InvocationHandler failRuleWrite = (proxy, method, args) -> {
                    if (StringUtils.equals(method.getName(), "saveEntity")) {
                        EntityRecord record = (EntityRecord) args[0];
                        if (record.getRef().equals(rule) && failWrite.compareAndSet(true, false)) {
                            throw original;
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                };
                return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(),
                        new Class<?>[] { StorageAccess.class }, failRuleWrite);
            });

            // 通过扩展创建管理端，并建立未受故障影响的服务记录。
            observed.setManagementToken("secret");
            MetConfiguration adminConfiguration = new MetConfiguration().addExtension(observed);

            try (MetContext adminContext = adminConfiguration.buildContext()) {
                RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
                MetServiceAdmin service = admin.getServiceAdmin("echo");
                service.putMeta("owner", "team");
                String revision = storage.getEntity(owner).getRevision();

                // 触发一次写入故障，原异常上抛且服务记录不变。
                failWrite.set(true);
                IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                    service.updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
                });
                assertSame(original, failure.getCause());
                assertNull(storage.getEntity(rule));
                assertEquals(revision, storage.getEntity(owner).getRevision());

                // 故障解除后可写入；重复写入相同正文不增加记录版本。
                service.updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
                String savedRevision = storage.getEntity(rule).getRevision();
                service.updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
                assertEquals(savedRevision, storage.getEntity(rule).getRevision());
                assertEquals(revision, storage.getEntity(owner).getRevision());
                assertEquals("team", RegistryGovernanceUtils.read(storage, owner).getMetadata().get("owner"));
            }
        }
    }
}
