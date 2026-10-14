/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper.scenario;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.scenario.example.EchoService;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 从公开配置启动两个应用，通过 ZooKeeper 自动发现后发起真实 RPC。 */
public class ZookeeperDiscoveryTest {
    @Test(timeout = 30000)
    public void subscriberDiscoversAndCallsPublishedService() throws Exception {
        // 本地服务端仅替代外部 ZooKeeper，应用接入仍使用正式扩展。
        try (TestingServer zookeeper = new TestingServer()) {
            ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
            registry.setAddress(zookeeper.getConnectString());
            MetConfiguration provider = new MetConfiguration().addExtension(registry);
            provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            MetConfiguration consumer = new MetConfiguration().addExtension(registry);
            consumer.connector("tcp").protocol("rsf");
            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                // 两端上下文独立，消费端不配置服务端地址，也没有本地实现可调用。
                server.start();
                client.start();
                server.publisher() //
                        .rsfService(EchoService.class, value -> "echo:" + value) //
                        .register();
                MetBindInfo<EchoService> service = client.publisher().rsfService(EchoService.class).register();

                // 只等待自动订阅更新地址本，不用主动拉取代替服务发现链路。
                String serviceId = service.getBindID();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (client.getUpdater().queryAvailableAddresses(serviceId).isEmpty()) {
                    assertTrue("ZooKeeper did not publish the provider address", System.nanoTime() < deadline);
                    TimeUnit.MILLISECONDS.sleep(20);
                }

                EchoService remote = client.getRsfClient().getRemote(service);
                assertEquals("echo:Meteor", remote.echo("Meteor"));
            }
        }
    }
}
