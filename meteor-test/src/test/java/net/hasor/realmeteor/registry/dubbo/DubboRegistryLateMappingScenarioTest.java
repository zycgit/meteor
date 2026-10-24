/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.assertInterfaceProvidersAbsent;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.awaitAddresses;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 服务到应用的映射在引用建立后才出现，应用发现必须由真实 ZooKeeper 通知驱动。 */
public class DubboRegistryLateMappingScenarioTest {
    @Test(timeout = 90000)
    public void existingReferenceDiscoversApplicationPublishedAfterMappingSubscription() throws Exception {
        // 全新的注册中心还没有目标服务的应用映射，先启动消费上下文和引用。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-late-mapping-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (MetContext client = consumer.buildContext(); //
                 CuratorFramework observer = CuratorFrameworkFactory.newClient( //
                         zookeeper.getConnectString(), new RetryOneTime(1000))) {
                observer.start();
                assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
                String nativeInterface = DubboInteropService.class.getName();
                assertNull(observer.checkExists().forPath("/dubbo/mapping/" + nativeInterface));
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                DubboInteropService remote = registry.reference(service);
                assertTrue(client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 原生提供者只注册应用，映射与实例随后到达，接口目录不能提供回退地址。
                try (DubboProcess provider = DubboProcess.start( //
                        "--command=server", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--register-mode=instance")) {
                    provider.awaitPort();
                    assertInterfaceProvidersAbsent(zookeeper.getConnectString(), nativeInterface);
                    awaitAddresses(client, service, 1);
                    assertEquals("late-mapping", remote.echo("late-mapping"));
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void closingLateMappingConsumerLeavesProviderAvailableToAnotherApplication() throws Exception {
        // 首个消费者先完成空映射订阅，随后到达的映射必须经过 ZooKeeper 事件线程。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration first = application(registryUrl, "meteor-late-mapping-first");
            first.connector("tcp").protocol("dubbo");
            MetConfiguration second = application(registryUrl, "meteor-late-mapping-second");
            second.connector("tcp").protocol("dubbo");
            try (MetContext firstClient = first.buildContext(); //
                 CuratorFramework observer = CuratorFrameworkFactory.newClient( //
                         zookeeper.getConnectString(), new RetryOneTime(1000))) {
                observer.start();
                assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
                String nativeInterface = DubboInteropService.class.getName();
                assertNull(observer.checkExists().forPath("/dubbo/mapping/" + nativeInterface));
                firstClient.start();
                MetBindInfo<DubboInteropService> firstService = firstClient.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboRegistryClient firstRegistry = firstClient.getExtension(DubboRegistryClient.class);
                DubboInteropService firstRemote = firstRegistry.reference(firstService);
                assertTrue(firstClient.getUpdater().queryAvailableAddresses(firstService.getBindID()).isEmpty());

                // 独立原生进程保持提供服务，消费上下文只管理自己的注册发现资源。
                try (DubboProcess provider = DubboProcess.start( //
                        "--command=server", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--register-mode=instance")) {
                    provider.awaitPort();
                    assertInterfaceProvidersAbsent(zookeeper.getConnectString(), nativeInterface);
                    awaitAddresses(firstClient, firstService, 1);
                    assertEquals("before-close", firstRemote.echo("before-close"));
                    firstClient.close();

                    // 关闭已处理晚映射的消费者须正常完成，且不能影响另一应用的新引用。
                    try (MetContext secondClient = second.buildContext()) {
                        secondClient.start();
                        MetBindInfo<DubboInteropService> secondService = secondClient.publisher() //
                                .rsfService(DubboInteropService.class) //
                                .register();
                        DubboRegistryClient secondRegistry = secondClient.getExtension(DubboRegistryClient.class);
                        DubboInteropService secondRemote = secondRegistry.reference(secondService);
                        awaitAddresses(secondClient, secondService, 1);
                        assertEquals("after-close", secondRemote.echo("after-close"));
                    }
                }
            }
        }
    }
}
