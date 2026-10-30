/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;

import static org.junit.Assert.assertTrue;

/** 无业务模拟，只提供配置与有界的异步状态等待。 */
final class DubboRegistryScenarioSupport {
    private DubboRegistryScenarioSupport() {
    }

    static MetConfiguration application(String registryUrl, String applicationName) {
        DubboRegistryConfiguration registry = new DubboRegistryConfiguration().setAddress(registryUrl);
        // 原生 SDK 位于另一个容器，注册主机必须是应用在测试网络中的可达地址。
        registry.setPublishHost(ContainerProcess.runnerHost());
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.setApplicationName(applicationName);
        return configuration;
    }

    static void tlsServer(ConnectorSettings connector, boolean clientAuthentication) {
        connector.option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test");
        if (clientAuthentication) {
            connector.option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test") //
                    .option("tlsClientAuth", "REQUIRE");
        }
    }

    static void tlsClient(ConnectorSettings connector, boolean clientIdentity) {
        connector.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test");
        if (clientIdentity) {
            connector.option("tlsKeyStore", "classpath:/certs/acceptance/client.p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }

    static String pem(String name) throws Exception {
        String resource = "/certs/acceptance/" + name + ".pem";
        return Path.of(DubboRegistryScenarioSupport.class.getResource(resource).toURI()).toString();
    }

    static void awaitAddresses(MetContext context, MetBindInfo<?> service, int expected) throws Exception {
        String serviceId = service.getBindID();
        await(() -> context.getUpdater().queryAvailableAddresses(serviceId).size() == expected);
    }

    static void assertInterfaceProvidersAbsent(String registryAddress, String serviceName) throws Exception {
        // 原生节点观测只证明对端没有接口级提供者，不能替代待测扩展的业务服务发现。
        try (CuratorFramework observer = CuratorFrameworkFactory.newClient(registryAddress, new RetryOneTime(1000))) {
            observer.start();
            assertTrue("Registry observer did not connect", observer.blockUntilConnected(10, TimeUnit.SECONDS));
            String providersPath = "/dubbo/" + serviceName + "/providers";
            if (observer.checkExists().forPath(providersPath) != null) {
                List<String> providers = observer.getChildren().forPath(providersPath);
                assertTrue("Application-only provider unexpectedly published an interface URL", providers.isEmpty());
            }
        }
    }

    static void await(BooleanSupplier condition) throws Exception {
        // 条件每轮重新读取；Future 只在观察到目标状态后完成，不用固定休眠代替完成通知。
        BasicFuture<Void> completed = new BasicFuture<>();
        ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor();
        try {
            observer.scheduleWithFixedDelay(() -> {
                try {
                    if (condition.getAsBoolean()) {
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
