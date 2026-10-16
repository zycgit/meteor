/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.nacos;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.ObjectUtils;
import net.hasor.example.registry.nacos.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.nacos.MockNacosSdk;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 通过公开入口验证发现与治理生命周期；SDK 对端模拟，业务 RPC 使用真实 TCP。
 */
public class NacosExtensionLifecycleTest {
    @Test
    public void extensionDiscoverySupportsNetworkInvocation() throws Exception {
        // 外部 SDK 使用模拟对端，应用仍从正式扩展和 TCP/RSF 入口启动。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration registry = sdk.configuration();
            registry.setManagementToken("secret");
            registry.setRefreshMillis(60000);
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration consumer = new MetConfiguration().addExtension(registry);
            consumer.connector("tcp").protocol("rsf");

            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                // 发布和订阅同一接口，两端启动后等待地址自动发现。
                MetBindInfo<EchoService> service = server.publisher() //
                        .rsfService(EchoService.class, value -> "sdk:" + value) //
                        .register();
                String serviceId = service.getBindID();
                client.publisher().rsfService(EchoService.class).register();
                server.start();
                client.start();
                await(() -> !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());

                // 独立消费者通过发现的地址调用服务，实际执行 TCP RPC。
                EchoService remote = client.getRsfClient().wrapper(EchoService.class);
                assertEquals("sdk:hello", remote.echo("hello"));
            }
        }
    }

    @Test
    public void extensionAppliesGovernanceFromSdkSubscription() throws Exception {
        // 注册一个提供者，周期补偿间隔大于本场景等待窗口。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration registry = sdk.configuration();
            registry.setManagementToken("secret");
            registry.setRefreshMillis(60000);
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

            try (MetContext server = provider.buildContext()) {
                // 发布接口并启动应用，等待提供者登记真正完成。
                MetBindInfo<EchoService> service = server.publisher() //
                        .rsfService(EchoService.class, value -> "sdk:" + value) //
                        .register();
                server.start();
                String serviceId = service.getBindID();
                RegistryAdmin admin = server.getExtension(RegistryAdmin.class);
                MetServiceAdmin serviceAdmin = admin.getServiceAdmin(serviceId);
                MetDirectoryQuery query = new MetDirectoryQuery();
                await(() -> serviceAdmin.providers(null, query).getItems().size() == 1);

                // 只等待存储通知应用元信息，不主动拉取或依赖周期补偿。
                serviceAdmin.putMeta("owner", "managed");
                await(() -> ObjectUtils.equals("managed", service.getMetaData("owner")));
                assertEquals(1, serviceAdmin.providers(null, query).getItems().size());
            }
        }
    }

    @Test
    public void providerOfflineRemovesTheDiscoveredAddress() throws Exception {
        // 外部 SDK 使用模拟对端，应用仍从正式扩展和 TCP/RSF 入口启动。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration registry = sdk.configuration();
            registry.setManagementToken("secret");
            registry.setRefreshMillis(60000);
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration consumer = new MetConfiguration().addExtension(registry);
            consumer.connector("tcp").protocol("rsf");

            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                // 发布和订阅同一接口，两端启动后等待地址自动发现。
                MetBindInfo<EchoService> service = server.publisher() //
                        .rsfService(EchoService.class, value -> "sdk:" + value) //
                        .register();
                String serviceId = service.getBindID();
                client.publisher().rsfService(EchoService.class).register();
                server.start();
                client.start();
                await(() -> !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());

                // 服务端下线撤回发布登记，客户端通过订阅移除地址。
                server.offline();
                await(() -> client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());
            }
        }
    }

    @Test
    public void closingContextsReleasesEverySdkListener() throws Exception {
        // 外部 SDK 使用模拟对端，应用仍从正式扩展和 TCP/RSF 入口启动。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration registry = sdk.configuration();
            registry.setManagementToken("secret");
            registry.setRefreshMillis(60000);
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration consumer = new MetConfiguration().addExtension(registry);
            consumer.connector("tcp").protocol("rsf");

            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                // 发布和订阅同一接口，两端启动后等待地址自动发现。
                MetBindInfo<EchoService> service = server.publisher() //
                        .rsfService(EchoService.class, value -> "sdk:" + value) //
                        .register();
                String serviceId = service.getBindID();
                client.publisher().rsfService(EchoService.class).register();
                server.start();
                client.start();
                await(() -> !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty());

                // 启动和发现已完成，确保关闭前确实安装了 SDK 监听器。
                assertTrue(sdk.listenerCount() > 0);
            }

            // 两个上下文退出后，没有遗留原生订阅。
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
