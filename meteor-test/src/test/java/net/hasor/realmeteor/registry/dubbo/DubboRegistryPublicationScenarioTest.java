/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.dubbo.common.URL;
import org.junit.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 服务发布后的元信息变更通过注册扩展的自动核对生效。 */
public class DubboRegistryPublicationScenarioTest {
    @Test(timeout = 90000)
    public void publicationIdentityChangedAfterStartBecomesNativeDefaultReferenceable() throws Exception {
        // 应用先启动并按默认身份发布，不把元信息提前设置来避开运行期变更。
        try (TestingServer zookeeper = new TestingServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-runtime-identity-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext(); //
                 CuratorFramework observer = CuratorFrameworkFactory.newClient( //
                         zookeeper.getConnectString(), new RetryOneTime(1000))) {
                server.start();
                observer.start();
                assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
                MetBindInfo<DubboInteropService> service = server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();
                String nativeInterface = DubboInteropService.class.getName();
                String providersPath = "/dubbo/" + nativeInterface + "/providers";
                await(() -> {
                    URL registered = registeredProvider(observer, providersPath);
                    if (registered == null) {
                        return false;
                    }
                    boolean expectedGroup = StringUtils.equals(registered.getParameter("group"), "default");
                    boolean expectedVersion = StringUtils.equals(registered.getParameter("version"), "1.0.0");
                    return expectedGroup && expectedVersion;
                });

                // 注册后改为原生空身份；核对周期须最终撤销旧 URL 并发布新 URL。
                service.setMetaData("dubbo.registry.group", "");
                service.setMetaData("dubbo.registry.version", "");
                await(() -> {
                    URL registered = registeredProvider(observer, providersPath);
                    if (registered == null) {
                        return false;
                    }
                    boolean nativeGroup = StringUtils.isBlank(registered.getParameter("group"));
                    boolean nativeVersion = StringUtils.isBlank(registered.getParameter("version"));
                    return nativeGroup && nativeVersion;
                });

                // 原生客户端不配置分组或版本，仅靠注册发现调用更新后的提供者。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl)) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    private static URL registeredProvider(CuratorFramework observer, String providersPath) {
        try {
            // ZooKeeper 中唯一的原生提供者 URL 是本场景的就绪条件。
            if (observer.checkExists().forPath(providersPath) == null) {
                return null;
            }
            List<String> providers = observer.getChildren().forPath(providersPath);
            if (providers.size() != 1) {
                return null;
            }
            return URL.valueOf(URL.decode(providers.get(0)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot observe native provider registration", failure);
        }
    }
}
