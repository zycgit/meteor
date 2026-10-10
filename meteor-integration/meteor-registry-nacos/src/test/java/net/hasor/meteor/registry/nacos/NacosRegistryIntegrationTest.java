/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetService;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Set METEOR_NACOS_SERVER to run against an isolated Nacos server.
 */
public class NacosRegistryIntegrationTest {

    @MetService(group = "nacos-integration")
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void autoDiscoveryRpcCasAndUnregister() throws Exception {
        // 真实服务测试只在明确配置独立 Nacos 环境时执行。
        String address = System.getenv("METEOR_NACOS_SERVER");
        Assume.assumeTrue(StringUtils.isNotBlank(address));
        MetConfiguration provider = new MetConfiguration();
        MetConfiguration consumer = new MetConfiguration();

        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        consumer.connector("tcp").protocol("rsf");

        NacosRegistryConfiguration registryConfig = new NacosRegistryConfiguration();
        registryConfig.setAddress(address);
        registryConfig.setGroup("METEOR_TEST_" + UUID.randomUUID());
        registryConfig.setRefreshMillis(100);
        provider.addExtension(registryConfig);
        consumer.addExtension(registryConfig);

        // 独立存储会话仅用于验证 CAS 和应用关闭后的临时记录清理。
        try (StorageAccess registry = registryConfig.openStorage()) {
            String serviceId;
            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                // 发布与订阅走正式扩展，客户端只配置出口，确保后续 RPC 经过网络。
                var service = server.publisher().rsfService(Echo.class, value -> "nacos:" + value).register();
                serviceId = service.getBindID();
                client.publisher().rsfService(Echo.class).register();
                server.start();
                client.start();

                await(() -> !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());
                List<InterAddress> addresses = client.getUpdater().queryAvailableAddresses(serviceId);
                assertFalse("Provider address was not discovered", addresses.isEmpty());
                assertEquals("nacos:中文", client.getRsfClient().wrapper(Echo.class).echo("中文"));
                EntityRef ref = new EntityRef(EntityType.SERVICE, serviceId);
                assertNotNull(registry.getEntity(ref));
                String rule = "def evalAddress(id, addresses) { addresses }";

                // 持久规则采用 CAS 更新，客户端通过存储订阅自动应用。
                EntityRef serviceRef = RegistryGovernanceUtils.service(serviceId);
                EntityRef ruleRef = RegistryGovernanceUtils.ruleRef(serviceRef, RuleTypeData.SERVICE_ROUTE);
                EntityRecord initial = new EntityRecord(ruleRef);
                EntRuleConfigurationData value = new EntRuleConfigurationData();
                value.setRule(rule);
                initial.setData(value);
                EntityRecord saved = registry.saveEntity(initial);
                assertNotEquals(initial.getRevision(), saved.getRevision());
                assertThrows(ConcurrentModificationException.class, () -> registry.saveEntity(initial));
                await(() -> {
                    String appliedRule = client.getUpdater().serviceRoute(serviceId);
                    return StringUtils.equals(rule, appliedRule);
                });
                assertEquals(rule, client.getUpdater().serviceRoute(serviceId));
            }

            // 应用退出后发布关系撤销，服务目录仍持久保留。
            long deadline = System.nanoTime() + 10_000_000_000L;
            RelationQuery query = new RelationQuery();
            query.setType(RelationType.PROVIDES);
            query.setTarget(new EntityRef(EntityType.SERVICE, serviceId));
            while (!registry.findRelations(query).records().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(registry.findRelations(query).records().isEmpty());
            assertNotNull(registry.getEntity(new EntityRef(EntityType.SERVICE, serviceId)));
        }
    }

    @Test
    public void extensionUsesSdkSubscriptionsForGovernanceAndReleasesListeners() throws Exception {
        // 仅模拟外部 SDK；扩展、注册中心存储、治理应用与 TCP RPC 使用实际实现。
        try (NacosTestServer sdk = new NacosTestServer()) {
            NacosRegistryConfiguration registry = sdk.configuration();
            registry.setManagementToken("secret");
            registry.setRefreshMillis(60000);
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration consumer = new MetConfiguration().addExtension(registry);
            consumer.connector("tcp").protocol("rsf");

            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                var service = server.publisher().rsfService(Echo.class, value -> "sdk:" + value).register();
                String serviceId = service.getBindID();
                client.publisher().rsfService(Echo.class).register();
                server.start();
                client.start();
                server.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
                client.getExtension(RegistryClient.class).pullSync().get(10, TimeUnit.SECONDS);
                assertEquals("sdk:hello", client.getRsfClient().wrapper(Echo.class).echo("hello"));

                // 下发治理后只等待存储通知，周期补偿间隔大于本次等待窗口。
                RegistryAdmin admin = server.getExtension(RegistryAdmin.class);
                admin.getServiceAdmin(serviceId).putMeta("owner", "managed");
                await(() -> ObjectUtils.equals("managed", service.getMetaData("owner")));
                assertEquals(1, admin.getServiceAdmin(serviceId).providers(null).length);

                // 应用下线撤回发布关系，消费者自动移除地址。
                server.offline();
                await(() -> client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());
            }

            // 所有上下文关闭后，SDK 原生监听器应被完全释放。
            assertEquals(0, sdk.listenerCount());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Nacos extension did not converge", condition.getAsBoolean());
    }

}
