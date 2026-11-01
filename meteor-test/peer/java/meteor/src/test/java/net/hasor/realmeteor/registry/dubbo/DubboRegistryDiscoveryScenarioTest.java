/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.registry.dubbo.RegistryEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.jupiter.api.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.awaitAddresses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 公开发布、引用和生命周期入口驱动的自动注册发现。 */
@NxnRpc(name = "dubbo", version = "3")
public class DubboRegistryDiscoveryScenarioTest {
    @NxnRpc(name = "dubbo")
    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void closingConsumerDoesNotRemoveAnotherApplicationsProvider() throws Exception {
        // 同一注册中心服务多个上下文，每个扩展只拥有本应用的注册会话。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-independent-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            MetConfiguration first = application(registryUrl, "meteor-first-consumer");
            first.connector("tcp").protocol("dubbo");
            MetConfiguration second = application(registryUrl, "meteor-second-consumer");
            second.connector("tcp").protocol("dubbo");
            try (MetContext server = provider.buildContext(); //
                 MetContext firstClient = first.buildContext()) {
                server.start();
                firstClient.start();
                server.publisher().rsfService(RegistryEchoService.class, value -> value).register();
                DubboRegistryClient firstRegistry = firstClient.getExtension(DubboRegistryClient.class);
                MetBindInfo<RegistryEchoService> firstService = firstClient.publisher() //
                        .rsfService(RegistryEchoService.class) //
                        .register();
                RegistryEchoService firstRemote = firstRegistry.reference(firstService);
                awaitAddresses(firstClient, firstService, 1);
                assertEquals("before-close", firstRemote.echo("before-close"));

                // 关闭首个消费者后，新的独立应用仍能通过注册中心发现原来的提供者。
                firstClient.close();
                try (MetContext secondClient = second.buildContext()) {
                    secondClient.start();
                    DubboRegistryClient secondRegistry = secondClient.getExtension(DubboRegistryClient.class);
                    MetBindInfo<RegistryEchoService> secondService = secondClient.publisher() //
                            .rsfService(RegistryEchoService.class) //
                            .register();
                    RegistryEchoService secondRemote = secondRegistry.reference(secondService);
                    awaitAddresses(secondClient, secondService, 1);
                    assertEquals("after-close", secondRemote.echo("after-close"));
                }
            }
        }
    }

    @NxnRpc(name = "dubbo", version = "2")
    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void existingSubscriptionDiscoversNewDubbo27Provider() throws Exception {
        // 先建立没有提供者的订阅，不能通过主动 refresh 代替后续注册通知。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-late-legacy-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (MetContext client = consumer.buildContext()) {
                client.start();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 原生 2.7 服务后来上线，已存在的代理在地址通知生效后直接可用。
                try (DubboProcess provider = DubboProcess.startLegacy( //
                        "--command=server", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0")) {
                    provider.awaitPort();
                    awaitAddresses(client, service, 1);
                    assertEquals("late-2.7", remote.echo("late-2.7"));
                }
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void existingSubscriptionDiscoversNewDubbo33Provider() throws Exception {
        // 引用入口允许先于提供者启动，初始无地址不改变其服务身份。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-late-current-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (MetContext client = consumer.buildContext()) {
                client.start();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 原生 3.3 注册后通过已有订阅更新地址，业务层无需重建代理。
                try (DubboProcess provider = DubboProcess.start( //
                        "--command=server", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0")) {
                    provider.awaitPort();
                    awaitAddresses(client, service, 1);
                    assertEquals("late-3.3", remote.echo("late-3.3"));
                }
            }
        }
    }

    @NxnRpc(name = "dubbo", version = "2")
    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo27ProviderShutdownWithdrawsDiscoveredAddress() throws Exception {
        // 先完成原生服务发现与真实调用，排除测试初始本就没有地址的假阳性。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-legacy-withdrawal");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("before-exit", remote.echo("before-exit"));

                // 关闭的是原生应用；Meteor 只观察订阅更新，不主动拉取或删除地址。
                provider.close();
                awaitAddresses(client, service, 0);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo33ProviderShutdownWithdrawsDiscoveredAddress() throws Exception {
        // 原生 3.3 提供者先被正常引用，其退出才是本场景唯一的状态变化。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-current-withdrawal");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("before-exit", remote.echo("before-exit"));

                // 正常注销触发已有订阅收敛到空地址本，不留下过期业务端点。
                provider.close();
                awaitAddresses(client, service, 0);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
            }
        }
    }

    @NxnRpc(name = "dubbo")
    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorProviderShutdownWithdrawsDiscoveredAddress() throws Exception {
        // 两个独立应用共享注册中心，消费方不持有提供者实现。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-closing-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            MetConfiguration consumer = application(registryUrl, "meteor-watching-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                server.start();
                client.start();
                server.publisher().rsfService(RegistryEchoService.class, value -> value).register();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<RegistryEchoService> service = client.publisher() //
                        .rsfService(RegistryEchoService.class) //
                        .register();
                RegistryEchoService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("available", remote.echo("available"));

                // 创建者关闭提供者后，注册扩展撤销它拥有的注册信息。
                server.close();
                awaitAddresses(client, service, 0);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
            }
        }
    }
}
