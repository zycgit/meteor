/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.sdk;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 通过两个独立测试 Java 入口验证 SDK 原生 ZooKeeper 注册发现，不向消费者传递业务地址。 */
public class NativeRegistryScenarioTest {
    @Test(timeout = 240000)
    public void dubboSdkConsumerDiscoversMeteorProvider() throws Exception {
        // 原生 Dubbo 不保留 URL chroot，双方使用当前场景独有的服务分组。
        String group = "order-" + UUID.randomUUID();
        try (ZookeeperTestServer registry = ZookeeperTestServer.dedicated()) {
            try (SdkPeerProcess provider = SdkPeerProcess //
                    .meteorMain("dubbo", "NativeRegistryProvider", null, "dubbo", registry.getConnectString(), group)) {
                provider.awaitReady("Order provider is ready: ");

                // 官方 SDK 使用隔离类路径，从同一注册中心自动发现 Meteor 提供者。
                try (SdkPeerProcess consumer = SdkPeerProcess //
                        .sdkMain("dubbo", "RegistryConsumer", null, registry.getConnectString(), group)) {
                    JsonNode order = consumer.awaitOrder();

                    // 保存和查询均经过原生发现得到的远程服务，验证完整订单而非仅检查进程退出。
                    assertEquals(1001, order.required("id").intValue());
                    assertEquals("created by dubbo-sdk-registry", order.required("description").textValue());
                    assertEquals("bWV0ZW9yLW9yZGVy", order.required("attachment").textValue());
                }
            }
        }
    }

    @Test(timeout = 240000)
    public void meteorConsumerDiscoversDubboSdkProvider() throws Exception {
        // SDK 和 Meteor 使用同一独立分组，注册发现不预置业务地址。
        String group = "order-" + UUID.randomUUID();
        try (ZookeeperTestServer registry = ZookeeperTestServer.dedicated()) {
            try (SdkPeerProcess provider = SdkPeerProcess //
                    .sdkMain("dubbo", "RegistryProvider", null, registry.getConnectString(), group)) {
                provider.awaitReady("SDK order provider is ready: ");

                // Meteor 从测试入口连接相同注册中心，不通过测试辅助代码查询或注入 SDK 地址。
                try (SdkPeerProcess consumer = SdkPeerProcess //
                        .meteorMain("dubbo", "NativeRegistryConsumer", null, "dubbo", registry.getConnectString(), group)) {
                    JsonNode order = consumer.awaitOrder();

                    // Meteor 保存的内容必须由 SDK 服务端返回，涵盖编号、文本和二进制附件。
                    assertEquals(1001, order.required("id").intValue());
                    assertEquals("created by dubbo-registry", order.required("description").textValue());
                    assertEquals("bWV0ZW9yLW9yZGVy", order.required("attachment").textValue());
                }
            }
        }
    }
}
