/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.registry.dubbo.RegistryEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 注册中心扩展的基础公开入口验证；官方对端与恢复矩阵由 meteor-test 承担。 */
public class DubboRegistryBootstrapScenarioTest {
    @Test(timeout = 60000)
    public void consumerDiscoversServicePublishedAfterBothApplicationsStarted() throws Exception {
        // 两端只配置注册中心，消费方没有监听端口或业务直连地址。
        try (TestingServer zookeeper = new TestingServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-discovery-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            MetConfiguration consumer = application(registryUrl, "meteor-discovery-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (MetContext server = provider.buildContext(); //
                 MetContext client = consumer.buildContext()) {
                server.start();
                client.start();
                server.publisher() //
                        .rsfService(RegistryEchoService.class, value -> "published:" + value) //
                        .register();

                // 发布与订阅都发生在容器启动后，调用走注册中心发现的网络地址。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<RegistryEchoService> service = client.publisher() //
                        .rsfService(RegistryEchoService.class) //
                        .register();
                RegistryEchoService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertTrue(client.getListenAddresses("dubbo").isEmpty());
                assertEquals("published:Meteor", remote.echo("Meteor"));
            }
        }
    }

    private static MetConfiguration application(String registryUrl, String applicationName) {
        DubboRegistryConfiguration registry = new DubboRegistryConfiguration().setAddress(registryUrl);
        registry.setPublishHost("127.0.0.1");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.setApplicationName(applicationName);
        return configuration;
    }

    private static void awaitAddresses(MetContext context, MetBindInfo<?> service, int expected) throws Exception {
        // 只观察公开地址本，等待注册通知到达；不主动刷新或注入业务地址。
        BasicFuture<Void> completed = new BasicFuture<>();
        ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor();
        try {
            observer.scheduleWithFixedDelay(() -> {
                try {
                    int count = context.getUpdater().queryAvailableAddresses(service.getBindID()).size();
                    if (count == expected) {
                        completed.completed(null);
                    }
                } catch (Throwable failure) {
                    completed.failed(failure);
                }
            }, 0, 20, TimeUnit.MILLISECONDS);
            completed.get(15, TimeUnit.SECONDS);
        } finally {
            observer.shutdownNow();
        }
    }
}
