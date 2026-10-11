/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import org.junit.Test;
import static org.junit.Assert.*;

/** 用扩展的管理端写入治理配置，通过客户端拉取后检查应用真实状态。 */
public class ConfigurationApplicationTest {
    @Test
    public void directMetadataEditsAndRegistryReplacementHaveDifferentScopes() throws Exception {
        MetConfiguration config = applicationConfiguration();

        try (MetContext context = config.buildContext()) {
            // 启动前声明服务，避免注册事件与后续治理断言交错。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            MetBindInfo<Echo> other = context.publisher().rsfService(Echo.class).name("other").register();
            context.start();
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            RegistryClient client = context.getExtension(RegistryClient.class);
            TargetAdmin targets = context.getExtension(TargetAdmin.class);

            // 直连写入只处理指定键，保留应用已有元信息。
            String id = service.getBindID();
            MetServiceAdmin direct = targets.getServiceAdminByAddress(context.bindAddress("rsf"), id);
            service.setMetaData("application", "local");
            other.setMetaData("local", "untouched");
            direct.putMeta("managed", "direct");
            assertEquals("local", service.getMetaData("application"));
            assertEquals("direct", service.getMetaData("managed"));

            // 整份下发依据应用当前键替换，不保留应用自行修改或新增的旧数据。
            service.removeMetaData("application");
            service.setMetaData("managed", "changed by application");
            service.setMetaData("new", true);
            admin.getServiceAdmin(id).putMeta("replacement", "stored");
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertEquals(Map.of("replacement", "stored"), metadata(service));
            assertEquals(Map.of("local", "untouched"), metadata(other));

            // 直连增删只处理指定的键，既不需要拉取，也不回写注册中心。
            direct.putMeta(Map.of("direct", "value"));
            assertEquals(Map.of("replacement", "stored", "direct", "value"), metadata(service));
            direct.removeMeta("direct", "missing");
            assertEquals(Map.of("replacement", "stored"), metadata(service));
        }
    }

    @Test
    public void fullMetadataReplacementValidatesBeforeWritingAndReleasesOldCapacity() throws Exception {
        MetConfiguration config = applicationConfiguration();

        try (MetContext context = config.buildContext()) {
            // 启动前声明服务，避免注册事件与后续治理断言交错。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.publisher().rsfService(Echo.class).name("other").register();
            context.start();
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 分别准备满容量的本地元信息和待下发元信息。
            Map<String, Object> local = new LinkedHashMap<>();
            Map<String, Object> replacement = new LinkedHashMap<>();
            for (int i = 0; i < MetBindInfo.MAX_METADATA_SIZE; i++) {
                local.put("local" + i, i);
                replacement.put("remote" + i, i);
            }
            service.setMetaData(local);
            admin.getClusterAdmin().putMeta(replacement);
            MetServiceAdmin serviceAdmin = admin.getServiceAdmin(service.getBindID());
            serviceAdmin.putMeta("extra", "overflow");

            // 各层分别有效，合并后超限必须反馈到主动拉取的 Future，原值保持不变。
            assertThrows(ExecutionException.class, () -> client.pullSync(service.getBindID()).get(5, TimeUnit.SECONDS));
            assertEquals(local, metadata(service));

            // 移除超限项后，整份替换应先释放旧容量再应用新内容。
            serviceAdmin.removeMeta("extra");
            client.pullSync(service.getBindID()).get(5, TimeUnit.SECONDS);
            assertEquals(replacement, metadata(service));
        }
    }

    @Test
    public void clearingGovernanceDeletesCurrentValuesWithoutRestoringHistory() throws Exception {
        MetConfiguration config = applicationConfiguration();

        try (MetContext context = config.buildContext()) {
            // 启动前声明服务，避免注册事件与后续治理断言交错。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.publisher().rsfService(Echo.class).name("other").register();
            context.start();
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 先设置应用自己的规则和元信息，作为治理覆盖前的状态。
            String id = service.getBindID();
            String local = "<route><return value='addresses'/></route>";
            context.getUpdater().updateServiceRoute(id, local);
            service.setMetaData("owner", "local");
            service.setMetaData("unmanaged", "local");
            service.setMetaData("object", new Object());

            // 拉取注册中心配置后，本地旧值被当前治理结果替换。
            MetServiceAdmin serviceAdmin = admin.getServiceAdmin(id);
            serviceAdmin.updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
            serviceAdmin.putMeta(Map.of("owner", "shared", "added", "value", "object", "shared"));
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertNull(context.getUpdater().serviceRoute(id));
            assertEquals("shared", service.getMetaData("owner"));
            assertNull(service.getMetaData("unmanaged"));

            // 清除治理后再次拉取，删除当前值且不恢复更早的本地数据。
            service.setMetaData("later", "application");
            serviceAdmin.removeMeta("owner", "added", "object");
            serviceAdmin.resetRule(MetRuleType.FLOW_CONTROL);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertNull(context.getUpdater().serviceRoute(id));
            assertTrue(service.getMetaKeys().isEmpty());
        }
    }

    @Test
    public void directQueriesReadCurrentRulesAndRegistrySwitchesReapplyStoredContent() throws Exception {
        MetConfiguration config = applicationConfiguration();

        try (MetContext context = config.buildContext()) {
            // 启动前声明服务，避免注册事件与后续治理断言交错。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.publisher().rsfService(Echo.class).name("other").register();
            context.start();
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            RegistryClient client = context.getExtension(RegistryClient.class);
            TargetAdmin targets = context.getExtension(TargetAdmin.class);

            // 应用自行更新规则后，直连查询读取当前真实正文。
            String id = service.getBindID();
            MetServiceAdmin direct = targets.getServiceAdminByAddress(context.bindAddress("rsf"), id);
            String changed = "<route><!-- override --><return value='addresses'/></route>";
            context.getUpdater().updateServiceRoute(id, changed);
            assertEquals(changed, direct.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertTrue(direct.isRule(MetRuleType.SERVICE_ROUTE));

            // 注册中心保留被禁用的正文，应用只接收清空或重新应用的结果。
            MetServiceAdmin stored = admin.getServiceAdmin(id);
            stored.updateRule(MetRuleType.SERVICE_ROUTE, changed);
            stored.disableRule(MetRuleType.SERVICE_ROUTE);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertNull(direct.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertFalse(direct.isRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(changed, stored.fetchRule(MetRuleType.SERVICE_ROUTE));

            // 重新启用时应用注册中心最新正文。
            String replacement = "<route><return value='{}'/></route>";
            stored.updateRule(MetRuleType.SERVICE_ROUTE, replacement);
            stored.enableRule(MetRuleType.SERVICE_ROUTE);
            client.pullSync(id).get(5, TimeUnit.SECONDS);
            assertEquals(replacement, direct.fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    private static MetConfiguration applicationConfiguration() {
        // 隔离显式拉取与后台订阅，只在本测试主动拉取时应用配置。
        StorageProbe probe = new StorageProbe();
        probe.setNotifications(false);
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
        registry.setManagementToken("secret");
        registry.setRefreshMillis(600000);
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        application.addExtension(registry);
        return application;
    }

    private static Map<String, Object> metadata(MetBindInfo<?> service) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : service.getMetaKeys()) {
            values.put(key, service.getMetaData(key));
        }
        return values;
    }
}
