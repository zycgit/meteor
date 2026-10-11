/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlGovernanceTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void allRuleTypesRoundTripThroughStorageAndSubscriptionWithoutEngineNames() throws Exception {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        try (MetContext application = configuration.buildContext(); //
             StorageAccess observer = registry.openStorage()) {
            // 正式扩展启动后，由管理接口写入，客户端通过存储订阅应用配置。
            String id = application.publisher().rsfService(Echo.class).register().getBindID();
            application.start();
            RegistryAdmin registryAdmin = application.getExtension(RegistryAdmin.class);
            MetServiceAdmin admin = registryAdmin.getServiceAdmin(id);
            MetUpdater updater = application.getUpdater();
            for (MetRuleType type : MetRuleType.values()) {
                String xml = "<route><return value='addresses'/></route>";
                if (type == MetRuleType.FLOW_CONTROL) {
                    xml = "<controlSet/>";
                }
                String expected = xml;
                admin.updateRule(type, xml);
                RegistryTestUtils.await(() -> StringUtils.equals(expected, current(updater, id, type)));
                EntityRef owner = RegistryGovernanceUtils.service(id);
                EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, RuleTypeData.valueOf(type.name()));
                EntRuleConfigurationData stored = (EntRuleConfigurationData) observer.getEntity(ref).getData();
                assertEquals(xml, stored.getRule());

                // 错误 XML 在管理端报告，存储与应用继续保留上一份有效规则。
                assertFalse(admin.validateRule(type, "<invalid/>").isValid());
                assertThrows(IllegalArgumentException.class, () -> admin.updateRule(type, "<invalid/>"));
                assertEquals(xml, admin.fetchRule(type));
                assertEquals(xml, current(updater, id, type));

                // 停用只清空应用规则，启用恢复正文；重置移除本层正文。
                admin.disableRule(type);
                RegistryTestUtils.await(() -> current(updater, id, type) == null);
                assertEquals(xml, admin.fetchRule(type));
                admin.enableRule(type);
                RegistryTestUtils.await(() -> StringUtils.equals(expected, current(updater, id, type)));
                admin.resetRule(type);
                RegistryTestUtils.await(() -> current(updater, id, type) == null);
            }
        }
    }

    private static String current(MetUpdater updater, String id, MetRuleType type) {
        return switch (type) {
            case SERVICE_ROUTE -> updater.serviceRoute(id);
            case METHOD_ROUTE -> updater.methodRoute(id);
            case ARGS_ROUTE -> updater.argsRoute(id);
            case FLOW_CONTROL -> updater.flowControl(id);
        };
    }
}
