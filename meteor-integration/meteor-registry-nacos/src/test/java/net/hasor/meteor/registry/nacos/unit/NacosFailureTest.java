/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.ConcurrentModificationException;
import java.util.Map;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

public class NacosFailureTest {
    @Test
    public void directoryContentionFailsWithoutPublishingAnIncompleteService() throws Exception {
        // 目录 CAS 连续竞争失败，耗尽存储层的有界重试。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntServiceCatalogData service = new EntServiceCatalogData();
            service.setServiceId("busy");
            sdk.conflicts = 8;

            // 失败必须可感知，且不能留下只有部分字段的服务登记。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveService(service));
            assertNull(storage.getService("busy"));
        }
    }

    @Test
    public void serviceSaveRecoversAfterDirectoryContentionEnds() throws Exception {
        // 第一次保存耗尽 CAS 重试，保留同一个待提交对象。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntServiceCatalogData service = new EntServiceCatalogData();
            service.setServiceId("busy");
            sdk.conflicts = 8;
            assertThrows(ConcurrentModificationException.class, () -> storage.saveService(service));

            // 竞争结束后重试原操作可以完整写入，不受上次失败污染。
            storage.saveService(service);
            assertNotNull(storage.getService("busy"));
        }
    }

    @Test
    public void failedMemberPublicationCanBeCompletedByRetryingTheSameService() throws Exception {
        // 实例已上线，在服务成员的写入处注入失败。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
            instance.setInstanceId("node");
            instance.setApplicationName("orders");
            storage.registerInstance(instance);
            EntServiceRuntimeData service = new EntServiceRuntimeData();
            service.setServiceId("echo");
            service.setRole(MetServiceType.Provider);
            sdk.rejectPrimary = true;
            assertThrows(IllegalStateException.class, () -> storage.registerService("node", service));
            assertNull(storage.getMember("echo", MetServiceType.Provider, "node"));

            // 单次失败可能留下本机清单，重试仍必须补上服务下的完整成员。
            sdk.rejectPrimary = false;
            storage.registerService("node", service);
            assertNotNull(storage.getInstanceService("node", "echo"));
            assertNotNull(storage.getMember("echo", MetServiceType.Provider, "node"));
        }
    }

    @Test
    public void unavailableConfigReadPropagatesTheOriginalFailure() throws Exception {
        // 存储打开后，让 Config SDK 读取抛出连接故障。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            sdk.available = false;

            // 点查将原始错误返回调用方，不伪装成没有该服务。
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> storage.getService("echo"));
            assertEquals("Nacos unavailable", failure.getMessage());
        }
    }

    @Test
    public void staleMetadataCannotOverwriteARecreatedRecord() throws Exception {
        // 保存元信息并保留旧版本，然后删除、重新创建同一范围的记录。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntMetadataData body = new EntMetadataData();
            body.setMetadata(Map.of("owner", "first"));
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(body);
            storage.saveMetadata(null, "echo", initial);
            EntityRecord<EntMetadataData> old = storage.getMetadata(null, "echo");
            storage.deleteMetadata(null, "echo", old.getRevision());
            storage.saveMetadata(null, "echo", initial);

            // 删除前的旧版本不能覆盖重建后的正文。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "echo", old));
        }
    }

    @Test
    public void closingAttemptsBothSdkClientsWhenWithdrawalFails() throws Exception {
        // 撤回实例和两个 SDK 关闭分别失败，用于检查完整清理链路。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
            instance.setInstanceId("closing");
            instance.setApplicationName("orders");
            storage.registerInstance(instance);
            NamingService naming = sdk.namingClients.get(0);
            ConfigService configs = sdk.configClients.get(0);
            NacosException withdrawal = new NacosException(500, "withdraw failed");
            NacosException namingClose = new NacosException(500, "naming close failed");
            NacosException configClose = new NacosException(500, "config close failed");
            doThrow(withdrawal).when(naming).batchRegisterInstance(anyString(), anyString(), anyList());
            doThrow(namingClose).when(naming).shutDown();
            doThrow(configClose).when(configs).shutDown();

            // 首个错误作为主异常，其余清理错误保留，所有 SDK 都尝试关闭。
            Exception failure = assertThrows(Exception.class, storage::close);
            assertSame(withdrawal, failure);
            assertArrayEquals(new Throwable[] { namingClose, configClose }, failure.getSuppressed());
            verify(naming).shutDown();
            verify(configs).shutDown();
        }
    }

    @Test
    public void interruptedCasBackoffStopsBeforePublishing() throws Exception {
        // CAS 冲突触发退避前，中断当前调用线程。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntServiceCatalogData service = new EntServiceCatalogData();
            service.setServiceId("interrupted");
            sdk.conflicts = 8;
            Thread.currentThread().interrupt();

            // 中断直接返回，不继续写入；退出测试时清理线程中断标记。
            try {
                assertThrows(InterruptedException.class, () -> storage.saveService(service));
            } finally {
                Thread.interrupted();
            }
            assertNull(storage.getService("interrupted"));
        }
    }
}
