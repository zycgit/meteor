/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.assertInterfaceProvidersAbsent;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.awaitAddresses;
import static org.junit.Assert.assertEquals;

/** 应用级服务元信息中的原生空身份必须与便利引用保持一致。 */
public class DubboRegistryApplicationIdentityScenarioTest {
    @Test(timeout = 90000)
    public void referenceByClassDiscoversApplicationOnlyProviderWithoutGroupOrVersion() throws Exception {
        // 原生 3.3 仅发布应用实例，分组和版本均未设置。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-application-native-default");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, "--register-mode=instance"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                String nativeInterface = DubboInteropService.class.getName();
                assertInterfaceProvidersAbsent(zookeeper.getConnectString(), nativeInterface);
                client.start();

                // 不补充分组或版本，也不通过接口级 providers 目录获取地址。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                DubboInteropService remote = registry.reference(DubboInteropService.class);
                MetBindInfo<DubboInteropService> service = client.getServiceInfo("dubbo", nativeInterface);
                awaitAddresses(client, service, 1);
                assertEquals("application-native-default", remote.echo("application-native-default"));
            }
        }
    }
}
