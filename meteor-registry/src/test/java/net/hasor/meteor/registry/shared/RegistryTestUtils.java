/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared;
import java.util.function.BooleanSupplier;
import net.hasor.meteor.MetService;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import static org.junit.Assert.assertTrue;

/** 供管理、客户端和启动场景复用的测试服务与等待工具。 */
public final class RegistryTestUtils {
    private RegistryTestUtils() {
    }

    @MetService(group = "registry-client", version = "1")
    public interface Echo {
        String echo(String value);
    }

    public static boolean hasRegistrations(RegistryConfiguration configuration) throws Exception {
        EntityQuery query = new EntityQuery();
        query.setType(EntityType.INSTANCE);
        query.setSection(EntitySection.RUNTIME);

        try (StorageAccess storage = configuration.openStorage()) {
            return !storage.findEntities(query).records().isEmpty();
        }
    }

    public static boolean hasPublishedAddress(RegistryConfiguration configuration) {
        RelationQuery query = new RelationQuery();
        query.setType(RelationType.PROVIDES);

        try (StorageAccess storage = configuration.openStorage()) {
            for (RelationRecord record : storage.findRelations(query).records()) {
                RefServiceRelationData data = (RefServiceRelationData) record.getData();
                if (data.getAddresses().length > 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Registry client did not converge", condition.getAsBoolean());
    }
}
