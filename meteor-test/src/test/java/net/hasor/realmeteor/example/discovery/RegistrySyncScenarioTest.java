/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery;
import java.util.concurrent.TimeUnit;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.order.OrderDemoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 显式同步的完整、单服务范围分别展示，不混入自动发现验证。 */
public class RegistrySyncScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @Test
    public void pushAllLocalSubscriptions() throws Exception {
        // 两个服务 ID 复用同一订单接口，展示全量推送的范围。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration configuration = RegistryDiscoveryExample.consumer(registry, "rsf");
        configuration.setDataHome(this.data.newFolder().toPath());
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> first = context.publisher().rsfService(OrderDemoService.class) //
                    .name("first-order").protocol("rsf").register();
            MetBindInfo<OrderDemoService> second = context.publisher().rsfService(OrderDemoService.class) //
                    .name("second-order").protocol("rsf").register();

            // Future 结束表示本机服务登记已写入，无需网络业务调用探测。
            RegistryClient synchronization = context.getExtension(RegistryClient.class);
            synchronization.pushSync().get(5, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetDirectoryQuery query = new MetDirectoryQuery();
            int firstMembers = admin.getServiceAdmin(first.getBindID()).consumers(null, query).getItems().size();
            int secondMembers = admin.getServiceAdmin(second.getBindID()).consumers(null, query).getItems().size();
            assertEquals(1, firstMembers);
            assertEquals(1, secondMembers);
        }
    }

    @Test
    public void pushOneServiceKeepsOtherSubscription() throws Exception {
        // 先建立两个本地登记，单服务推送不能将另一个登记覆盖掉。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration configuration = RegistryDiscoveryExample.consumer(registry, "rsf");
        configuration.setDataHome(this.data.newFolder().toPath());
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> first = context.publisher().rsfService(OrderDemoService.class) //
                    .name("first-order").protocol("rsf").register();
            MetBindInfo<OrderDemoService> second = context.publisher().rsfService(OrderDemoService.class) //
                    .name("second-order").protocol("rsf").register();
            RegistryClient synchronization = context.getExtension(RegistryClient.class);
            synchronization.pushSync().get(5, TimeUnit.SECONDS);

            // 指定服务 ID 的增量提交保留其他发布订阅关系。
            synchronization.pushSync(first.getBindID()).get(5, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetDirectoryQuery query = new MetDirectoryQuery();
            int firstMembers = admin.getServiceAdmin(first.getBindID()).consumers(null, query).getItems().size();
            int secondMembers = admin.getServiceAdmin(second.getBindID()).consumers(null, query).getItems().size();
            assertEquals(1, firstMembers);
            assertEquals(1, secondMembers);
        }
    }

    @Test
    public void pullAllServicesAppliesBothMetadataValues() throws Exception {
        // 管理配置保存后，完整拉取覆盖本地声明的两个服务。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration configuration = RegistryDiscoveryExample.consumer(registry, "rsf");
        configuration.setDataHome(this.data.newFolder().toPath());
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> first = context.publisher().rsfService(OrderDemoService.class) //
                    .name("first-order").protocol("rsf").register();
            MetBindInfo<OrderDemoService> second = context.publisher().rsfService(OrderDemoService.class) //
                    .name("second-order").protocol("rsf").register();
            RegistryClient synchronization = context.getExtension(RegistryClient.class);
            synchronization.pushSync().get(5, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            admin.getServiceAdmin(first.getBindID()).putMeta("color", "blue");
            admin.getServiceAdmin(second.getBindID()).putMeta("color", "green");

            // Future 结束后两份配置均已应用；不把此操作称为自动订阅。
            synchronization.pullSync().get(5, TimeUnit.SECONDS);
            assertEquals("blue", first.getMetaData("color"));
            assertEquals("green", second.getMetaData("color"));
        }
    }

    @Test
    public void pullOneServiceRetainsOtherLocalSubscription() throws Exception {
        // 单服务拉取按服务 ID 定位，不删除同一上下文中的另一份契约。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration configuration = RegistryDiscoveryExample.consumer(registry, "rsf");
        configuration.setDataHome(this.data.newFolder().toPath());
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> first = context.publisher().rsfService(OrderDemoService.class) //
                    .name("first-order").protocol("rsf").register();
            MetBindInfo<OrderDemoService> second = context.publisher().rsfService(OrderDemoService.class) //
                    .name("second-order").protocol("rsf").register();
            RegistryClient synchronization = context.getExtension(RegistryClient.class);
            synchronization.pushSync().get(5, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            admin.getServiceAdmin(first.getBindID()).putMeta("color", "blue");

            // 只拉取第一个服务，目标配置生效且另一份订阅仍登记在目录中。
            synchronization.pullSync(first.getBindID()).get(5, TimeUnit.SECONDS);
            assertEquals("blue", first.getMetaData("color"));
            MetBindInfo<?> retained = context.getServiceInfo(second.getBindID());
            assertNotNull(retained);
            assertEquals(second.getBindID(), retained.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();
            int secondMembers = admin.getServiceAdmin(second.getBindID()).consumers(null, query).getItems().size();
            assertEquals(1, secondMembers);
        }
    }

    private static MemoryRegistryConfiguration registry() {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("scenario-admin");
        registry.setRefreshMillis(60000);
        return registry;
    }
}
