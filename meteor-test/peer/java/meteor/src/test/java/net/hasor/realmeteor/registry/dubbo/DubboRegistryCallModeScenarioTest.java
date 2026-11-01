/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.jupiter.api.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.awaitAddresses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Timeout;

/** 泛化和消息模式复用同一注册发现链路，不通过业务直连地址绕过引用。 */
@NxnRpc(name = "dubbo", version = "3")
public class DubboRegistryCallModeScenarioTest {
    @NxnRpc(name = "dubbo", version = "2")
    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo27GenericConsumerInvokesMeteorThroughRegistry() throws Exception {
        // Meteor 从公开入口发布普通 Java 接口，无需为官方泛化调用另注册服务。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-generic-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方 GenericService 只有注册中心地址，通过服务元信息解析多参数方法。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--scenario=generic")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.has("error"), result.toString());
                    assertEquals("hello:3", result.path("value").asText());
                }
            }
        }
    }

    @NxnRpc(name = "dubbo", version = "2")
    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo27ConsumerSendsOnewayToMeteorThroughRegistry() throws Exception {
        // 业务实现的独立信号表示真正执行完毕，不把官方本地写出当成远端回执。
        CountDownLatch delivered = new CountDownLatch(1);
        DubboInteropServiceImpl implementation = new DubboInteropServiceImpl() {
            @Override
            public void notice(String value) {
                super.notice(value);
                delivered.countDown();
            }
        };
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-oneway-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher().rsfService(DubboInteropService.class, implementation).register();

                // return=false 的官方引用同样从注册中心发现业务地址。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--scenario=oneway")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.has("error"), result.toString());
                    assertTrue(result.path("value").isNull());
                    assertTrue(delivered.await(5, TimeUnit.SECONDS));
                    assertEquals(1, implementation.notices());
                }
            }
        }
    }

    @NxnRpc(name = "dubbo", version = "2")
    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorSendsOnewayToDubbo27ProviderThroughRegistry() throws Exception {
        // 两份本地声明具有同一原生身份，通知声明与普通观察声明分开。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-oneway-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();
                String nativeInterface = DubboInteropService.class.getName();
                MetBindInfo<DubboInteropService> notification = client.publisher() //
                        .rsfService(DubboInteropService.class).name("notification") //
                        .aliasName("dubbo", nativeInterface).asMessage().register();
                MetBindInfo<DubboInteropService> observer = client.publisher() //
                        .rsfService(DubboInteropService.class).name("observer") //
                        .aliasName("dubbo", nativeInterface).register();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                registry.reference(notification);
                registry.reference(observer);
                awaitAddresses(client, notification, 1);
                awaitAddresses(client, observer, 1);

                // 普通客户端使用扩展写入的动态地址本，Future 只确认消息完整写出。
                MetClient caller = client.getRsfClient();
                Class<?>[] argumentTypes = { String.class };
                Object[] arguments = { "notification" };
                MetFuture written = caller.asyncInvoke(notification, "notice", argumentTypes, arguments);
                MetResponse accepted = written.get(5, TimeUnit.SECONDS);
                assertTrue(written.getRequest().isMessage());
                assertNull(accepted.getData());

                // 额外业务查询只用于观察执行，不是 oneway 协议的 ACK。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int count;
                do {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "Official provider did not execute the notification");
                    MetFuture observed = caller.asyncInvoke(observer, "notices", new Class<?>[0], new Object[0]);
                    count = (Integer) observed.getData(remaining, TimeUnit.NANOSECONDS);
                    assertFalse(observed.getRequest().isMessage());
                } while (count == 0);
                assertEquals(1, count);
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo33GenericConsumerInvokesMeteorThroughRegistry() throws Exception {
        // Meteor 从公开入口发布普通 Java 接口，无需为官方泛化调用另注册服务。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-generic-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方 GenericService 只有注册中心地址，通过服务元信息解析多参数方法。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--scenario=generic")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.has("error"), result.toString());
                    assertEquals("hello:3", result.path("value").asText());
                }
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dubbo33ConsumerSendsOnewayToMeteorThroughRegistry() throws Exception {
        // 业务实现的独立信号表示真正执行完毕，不把官方本地写出当成远端回执。
        CountDownLatch delivered = new CountDownLatch(1);
        DubboInteropServiceImpl implementation = new DubboInteropServiceImpl() {
            @Override
            public void notice(String value) {
                super.notice(value);
                delivered.countDown();
            }
        };
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-oneway-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher().rsfService(DubboInteropService.class, implementation).register();

                // return=false 的官方引用同样从注册中心发现业务地址。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--scenario=oneway")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.has("error"), result.toString());
                    assertTrue(result.path("value").isNull());
                    assertTrue(delivered.await(5, TimeUnit.SECONDS));
                    assertEquals(1, implementation.notices());
                }
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorSendsOnewayToDubbo33ProviderThroughRegistry() throws Exception {
        // 两份本地声明具有同一原生身份，通知声明与普通观察声明分开。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-oneway-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();
                String nativeInterface = DubboInteropService.class.getName();
                MetBindInfo<DubboInteropService> notification = client.publisher() //
                        .rsfService(DubboInteropService.class).name("notification") //
                        .aliasName("dubbo", nativeInterface).asMessage().register();
                MetBindInfo<DubboInteropService> observer = client.publisher() //
                        .rsfService(DubboInteropService.class).name("observer") //
                        .aliasName("dubbo", nativeInterface).register();
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                registry.reference(notification);
                registry.reference(observer);
                awaitAddresses(client, notification, 1);
                awaitAddresses(client, observer, 1);

                // 普通客户端使用扩展写入的动态地址本，Future 只确认消息完整写出。
                MetClient caller = client.getRsfClient();
                Class<?>[] argumentTypes = { String.class };
                Object[] arguments = { "notification" };
                MetFuture written = caller.asyncInvoke(notification, "notice", argumentTypes, arguments);
                MetResponse accepted = written.get(5, TimeUnit.SECONDS);
                assertTrue(written.getRequest().isMessage());
                assertNull(accepted.getData());

                // 额外业务查询只用于观察执行，不是 oneway 协议的 ACK。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int count;
                do {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "Official provider did not execute the notification");
                    MetFuture observed = caller.asyncInvoke(observer, "notices", new Class<?>[0], new Object[0]);
                    count = (Integer) observed.getData(remaining, TimeUnit.NANOSECONDS);
                    assertFalse(observed.getRequest().isMessage());
                } while (count == 0);
                assertEquals(1, count);
            }
        }
    }
}
