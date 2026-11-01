/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.junit.jupiter.api.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Timeout;

/** 同一个原生应用级代理经历提供者上下线或新端口重建，恢复时不重建引用或重试业务。 */
@NxnRpc(name = "dubbo", version = "3")
public class DubboRegistryRecoveryScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void applicationOnlyProxyRecoversAfterProviderOfflineAndOnline() throws Exception {
        // 冻结内核的 offline 暂停现有监听，online 在同一端口恢复。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-online-recovery-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext(); //
                 CuratorFramework observer = CuratorFrameworkFactory.newClient( //
                         zookeeper.getConnectString(), new RetryOneTime(1000))) {
                server.start();
                observer.start();
                assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();
                int originalPort = server.getListenAddress("dubbo").getPort();

                // 原生客户端强制应用级发现；第一次结果证明已持有一个可调用的代理。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--register-mode=instance", "--scenario=reconnect")) {
                    JsonNode before = consumer.awaitResult();
                    assertFalse(before.has("error"), before.toString());
                    assertEquals("before-reconnect", before.path("value").asText());

                    // 真实离线撤销应用实例，之后再恢复同一上下文的监听和注册。
                    server.offline();
                    String applicationPath = "/services/" + server.getApplicationName();
                    await(() -> applicationInstancesAbsent(observer, applicationPath));
                    server.online();
                    int resumedPort = server.getListenAddress("dubbo").getPort();
                    assertEquals(originalPort, resumedPort);
                    await(() -> applicationInstanceUsesPort(observer, applicationPath, resumedPort));

                    // 对端等待其官方目录恢复目标，再仅用原来的代理调用一次。
                    consumer.signal(Integer.toString(resumedPort));
                    JsonNode after = consumer.awaitResult();
                    assertFalse(after.has("error"), after.toString());
                    assertEquals("after-reconnect", after.path("value").asText());
                    assertEquals(resumedPort, after.path("port").asInt());
                }
            }
        }
    }

    @Test
    @Timeout(value = 90000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void applicationOnlyProxyFollowsProviderRecreatedOnNewPort() throws Exception {
        // 相同应用名重建上下文，业务引用继续使用原来的原生代理。
        try (ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-port-recovery-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext original = provider.buildContext(); //
                 CuratorFramework observer = CuratorFrameworkFactory.newClient( //
                         zookeeper.getConnectString(), new RetryOneTime(1000))) {
                original.start();
                observer.start();
                assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
                original.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();
                int originalPort = original.getListenAddress("dubbo").getPort();

                // 原生引用已经成功调用，关闭和重建期间不创建第二份引用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--register-mode=instance", "--scenario=reconnect")) {
                    JsonNode before = consumer.awaitResult();
                    assertFalse(before.has("error"), before.toString());
                    assertEquals("before-reconnect", before.path("value").asText());
                    original.close();
                    String applicationPath = "/services/" + original.getApplicationName();
                    await(() -> applicationInstancesAbsent(observer, applicationPath));

                    // 占住已释放的旧端口，确保新上下文的随机端口确实发生变化。
                    try (ServerSocket oldPort = new ServerSocket(); //
                         MetContext replacement = provider.buildContext()) {
                        oldPort.setReuseAddress(true);
                        oldPort.bind(new InetSocketAddress("0.0.0.0", originalPort));
                        replacement.start();
                        replacement.publisher() //
                                .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                                .register();
                        int replacementPort = replacement.getListenAddress("dubbo").getPort();
                        assertNotEquals(originalPort, replacementPort);
                        await(() -> applicationInstanceUsesPort(observer, applicationPath, replacementPort));

                        // 官方目录必须观察到新端口，随后同一代理只调用一次业务。
                        consumer.signal(Integer.toString(replacementPort));
                        JsonNode after = consumer.awaitResult();
                        assertFalse(after.has("error"), after.toString());
                        assertEquals("after-reconnect", after.path("value").asText());
                        assertEquals(replacementPort, after.path("port").asInt());
                    }
                }
            }
        }
    }

    private static boolean applicationInstancesAbsent(CuratorFramework observer, String applicationPath) {
        try {
            if (observer.checkExists().forPath(applicationPath) == null) {
                return true;
            }
            List<String> instances = observer.getChildren().forPath(applicationPath);
            return instances.isEmpty();
        } catch (NoNodeException absent) {
            return true;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot observe native application withdrawal", failure);
        }
    }

    private static boolean applicationInstanceUsesPort(CuratorFramework observer, String applicationPath, int port) {
        try {
            // Curator 的原生实例正文包含业务端口，只有唯一新实例出现后才认为发布完成。
            List<String> instances = observer.getChildren().forPath(applicationPath);
            if (instances.size() != 1) {
                return false;
            }
            String instancePath = applicationPath + "/" + instances.get(0);
            byte[] content = observer.getData().forPath(instancePath);
            JsonNode instance = JSON.readTree(content);
            return instance.path("port").asInt() == port;
        } catch (NoNodeException absent) {
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot observe native application endpoint", failure);
        }
    }
}
