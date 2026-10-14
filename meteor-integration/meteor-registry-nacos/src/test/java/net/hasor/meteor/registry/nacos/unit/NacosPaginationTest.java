/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.HashSet;
import java.util.Set;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class NacosPaginationTest {
    @Test
    public void serviceDirectoryUsesScopedPartitionsWithoutNamingEnumeration() throws Exception {
        // 准备多于一页的服务目录记录，SDK 仅模拟外部存储。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            for (int index = 0; index < 150; index++) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("echo-" + index);
                storage.saveService(service);
            }

            // 沿游标读取目录，验证每页上限及无遗漏、无重复。
            DirectoryQuery query = new DirectoryQuery();
            query.setLimit(17);
            Set<String> services = new HashSet<>();
            do {
                RecordPage<EntServiceCatalogData> page = storage.services(query);
                assertTrue(page.getItems().size() <= 17);
                for (EntServiceCatalogData service : page.getItems()) {
                    assertTrue(services.add(service.getServiceId()));
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
            assertEquals(150, services.size());
            verify(sdk.namingClients.get(0), never()).getServicesOfServer(anyInt(), anyInt(), anyString());
        }
    }

    @Test
    public void batchRegistrationPreservesEveryServiceOfOneInstance() throws Exception {
        // 为同一个实例连续登记多项服务，覆盖 SDK 批量替换语义。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
            instance.setInstanceId("node");
            instance.setApplicationName("orders");
            storage.registerInstance(instance);
            for (int index = 0; index < 120; index++) {
                EntServiceRuntimeData service = new EntServiceRuntimeData();
                service.setServiceId("echo-" + index);
                service.setRole(MetServiceType.Provider);
                storage.registerService("node", service);
            }

            // 从实例清单逐页读取，早期登记与最后登记的成员都应保留。
            DirectoryQuery query = new DirectoryQuery();
            query.setLimit(13);
            Set<String> services = new HashSet<>();
            do {
                RecordPage<EntServiceRuntimeData> page = storage.instanceServices("node", query);
                for (EntServiceRuntimeData service : page.getItems()) {
                    assertTrue(services.add(service.getServiceId()));
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
            assertEquals(120, services.size());
            assertNotNull(storage.getMember("echo-0", MetServiceType.Provider, "node"));
            assertNotNull(storage.getMember("echo-119", MetServiceType.Provider, "node"));
            verify(sdk.namingClients.get(0), never()).getServicesOfServer(anyInt(), anyInt(), anyString());
        }
    }
}
