/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.configuration;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.createDatabase;
import static net.hasor.meteor.registry.jdbc.unit.JdbcStorageData.*;
import static org.junit.Assert.*;

public class JdbcGovernanceLifetimeTest {
    @Test
    public void instanceStateAndMetadataFollowTheApplicationSession() throws Exception {
        // 管理端写入应用实例治理，临时记录仍归属于应用。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess observer = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            application.registerInstance(instance());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String owner = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class).get(0);
            String archive = jdbc.queryForList("SELECT body FROM meteor_instances", String.class).get(0);
            try (StorageAccess manager = configuration.openStorage()) {
                EntityRecord<EntConfigurationData> state = new EntityRecord<>();
                state.setData(new EntConfigurationData());
                state.getData().setBlock(true);
                manager.saveConfiguration("one", null, state);
                manager.saveMetadata("one", null, metadata());
            }
            assertTrue(observer.getConfiguration("one", null).getData().getBlock());
            assertNotNull(observer.getMetadata("one", null));

            // 管理端退出不清理应用治理，应用退出才移除两类临时正文。
            application.close();
            assertNull(observer.getConfiguration("one", null));
            assertNull(observer.getMetadata("one", null));
            String offlineSql = "SELECT session_id FROM meteor_instances" //
                    + " WHERE runtime_body IS NULL AND runtime_revision IS NULL" //
                    + " AND rule_controls IS NULL AND expires_at IS NULL";
            assertEquals(List.of(owner), jdbc.queryForList(offlineSql, String.class));
            assertEquals(List.of(archive), jdbc.queryForList("SELECT body FROM meteor_instances", String.class));
            String temporarySql = "SELECT body FROM meteor_governance WHERE session_id=?";
            assertTrue(jdbc.queryForList(temporarySql, new Object[] { owner }, String.class).isEmpty());
        }
    }

    @Test
    public void withdrawalRemovesOnlyInstanceServiceMetadata() throws Exception {
        // 实例范围和实例服务范围的元信息分别保存。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            application.registerService("one", service());
            manager.saveMetadata("one", null, metadata());
            manager.saveMetadata("one", "echo", metadata());

            // 服务撤回只清除交叉范围，再登记不会复活它。
            application.unregisterService("one", "echo");
            application.registerService("one", service());
            assertNull(manager.getMetadata("one", "echo"));
            assertNotNull(manager.getMetadata("one", null));
        }
    }

    @Test
    public void expiredRegistrationHidesInstanceMetadata() throws Exception {
        // 仅过期应用实例租约，保留管理端观察和操作能力。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            application.registerInstance(instance());
            manager.saveMetadata("one", null, metadata());
            String sql = "UPDATE meteor_instances SET expires_at=0 WHERE runtime_body IS NOT NULL";
            new JdbcTemplate(connection).executeUpdate(sql);

            // 失联后元信息不可见，也不允许管理员代应用恢复登记。
            assertNull(manager.getMetadata("one", null));
            assertThrows(IllegalStateException.class, () -> manager.saveMetadata("one", null, metadata()));
        }
    }

    @Test
    public void unregisteredInstanceCannotStoreState() throws Exception {
        // 临时配置必须关联真实应用登记，不能由管理写入制造离线实例。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess manager = configuration.openStorage()) {
            EntityRecord<EntConfigurationData> state = new EntityRecord<>();
            state.setData(new EntConfigurationData());
            assertThrows(IllegalStateException.class, () -> manager.saveConfiguration("offline", null, state));
            assertNull(manager.getConfiguration("offline", null));
        }
    }
}
