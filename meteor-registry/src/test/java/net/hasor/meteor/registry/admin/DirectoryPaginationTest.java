/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import org.junit.Test;
import static org.junit.Assert.*;

/** 大目录验证读取范围和单页契约，不作为真实集群性能基准。 */
public class DirectoryPaginationTest {
    @Test
    public void fiftyThousandArchivedInstancesDoNotExpandApplicationPageQueries() throws Exception {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        // 200 个应用，每个应用 250 个实例；会话退出后仍保留档案。
        try (StorageAccess writer = registry.openStorage()) {
            for (int app = 0; app < 200; app++) {
                for (int member = 0; member < 250; member++) {
                    EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
                    instance.setApplicationName("app-" + app);
                    instance.setInstanceId("app-" + app + "-node-" + member);
                    instance.setUnitName("default");
                    writer.registerInstance(instance);
                }
            }
        }
        Map<String, Integer> reads = new HashMap<>();
        DecoratedRegistryConfiguration observed = new DecoratedRegistryConfiguration(registry, delegate -> {
            return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(), new Class<?>[] { StorageAccess.class }, (proxy, method, args) -> {
                String operation = method.getName();
                reads.merge(operation, 1, Integer::sum);
                assertFalse("No enrichment reads", Set.of("getInstance", "instanceServices", "members").contains(operation));
                if (StringUtils.equals(operation, "instances")) {
                    assertEquals("app-42", args[0]);
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        });
        observed.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(observed);
        try (MetContext context = configuration.buildContext()) {
            MetClusterAdmin admin = context.getExtension(RegistryAdmin.class).getClusterAdmin();
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setLimit(25);
            assertEquals(25, admin.applications(query).getItems().size());
            assertEquals(Integer.valueOf(1), reads.get("applications"));
            assertFalse(reads.containsKey("instances"));

            // 进入一个应用后只读取该应用的 10 页档案，且没有重复或遗漏。
            Set<String> instances = new HashSet<>();
            do {
                MetPage<MetInstanceInfo> page = admin.instances("app-42", query);
                assertTrue(page.getItems().size() <= 25);
                for (MetInstanceInfo instance : page.getItems()) {
                    assertFalse(instance.isOnline());
                    assertTrue(instances.add(instance.getInstanceId()));
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
            assertEquals(250, instances.size());
            assertEquals(Integer.valueOf(10), reads.get("instances"));
        }
    }
}
