/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper.scenario;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.scenario.example.EchoService;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 公开发布与管理入口触发原生订阅，不通过主动拉取替代治理通知。 */
public class ZookeeperGovernanceTest {
    @Test(timeout = 30000)
    public void disablingInstanceServiceRuleAutomaticallyRemovesAppliedRoute() throws Exception {
        // 延长周期核对间隔，确保本场景由 ZooKeeper watch 驱动。
        try (TestingServer zookeeper = new TestingServer()) {
            ZookeeperRegistryConfiguration registry = registry(zookeeper.getConnectString());
            MetConfiguration configuration = new MetConfiguration().addExtension(registry);
            configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            try (MetContext context = configuration.buildContext(); //
                 StorageAccess storage = registry.openStorage()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class, value -> "echo:" + value) //
                        .register();
                String instanceId = context.getInstanceID();
                String serviceId = service.getBindID();
                awaitRegistration(storage, instanceId, serviceId);

                // 通过指定实例的服务管理入口配置并等待路由实际生效。
                RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
                MetServiceAdmin scoped = admin.getServiceAdmin(serviceId, instanceId);
                String xml = "<route><return value='addresses'/></route>";
                scoped.updateRule(MetRuleType.SERVICE_ROUTE, xml);
                await(() -> StringUtils.equals(xml, context.getUpdater().serviceRoute(serviceId)));

                // 禁用只修改规则控制，应用收到通知后清除实际路由。
                scoped.disableRule(MetRuleType.SERVICE_ROUTE);
                await(() -> context.getUpdater().serviceRoute(serviceId) == null);
                assertEquals(xml, scoped.fetchRule(MetRuleType.SERVICE_ROUTE));
            }
        }
    }

    @Test(timeout = 30000)
    public void enablingInstanceServiceRuleAutomaticallyRestoresStoredRoute() throws Exception {
        // 配置使用真实 ZooKeeper 服务，业务上下文经 bootstrap 启动。
        try (TestingServer zookeeper = new TestingServer()) {
            ZookeeperRegistryConfiguration registry = registry(zookeeper.getConnectString());
            MetConfiguration configuration = new MetConfiguration().addExtension(registry);
            configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            try (MetContext context = configuration.buildContext(); //
                 StorageAccess storage = registry.openStorage()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class, value -> "echo:" + value) //
                        .register();
                String instanceId = context.getInstanceID();
                String serviceId = service.getBindID();
                awaitRegistration(storage, instanceId, serviceId);

                // 准备已保存且已禁用的本实例服务规则。
                RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
                MetServiceAdmin scoped = admin.getServiceAdmin(serviceId, instanceId);
                String xml = "<route><return value='addresses'/></route>";
                scoped.updateRule(MetRuleType.SERVICE_ROUTE, xml);
                await(() -> StringUtils.equals(xml, context.getUpdater().serviceRoute(serviceId)));
                scoped.disableRule(MetRuleType.SERVICE_ROUTE);
                await(() -> context.getUpdater().serviceRoute(serviceId) == null);

                // 启用沿存储订阅恢复规则，不调用客户端 pullSync。
                scoped.enableRule(MetRuleType.SERVICE_ROUTE);
                await(() -> StringUtils.equals(xml, context.getUpdater().serviceRoute(serviceId)));
            }
        }
    }

    private static ZookeeperRegistryConfiguration registry(String address) {
        ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
        registry.setAddress(address);
        registry.setManagementToken("scenario-admin");
        registry.setRefreshMillis(600000);
        return registry;
    }

    private static void awaitRegistration(StorageAccess storage, String instanceId, String serviceId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (storage.getInstanceService(instanceId, serviceId) == null) {
            assertTrue("Service registration timed out", System.nanoTime() < deadline);
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue("Governance subscription timed out", System.nanoTime() < deadline);
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
