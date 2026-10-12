/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.ObjectUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证可精确观察通知与故障的 SDK 模拟场景；真实 Nacos 应用组合见 met-example。
 */
public class NacosRegistryIntegrationTest {
    @MetService(group = "nacos-integration")
    public interface Echo {
        String echo(String value);
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
