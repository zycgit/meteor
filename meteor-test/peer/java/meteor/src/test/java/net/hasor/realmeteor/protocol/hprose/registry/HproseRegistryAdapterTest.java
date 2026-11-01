/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.registry;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Node 官方 Hprose SDK 的显式注册适配，不把辅助进程的存储发现标记为 SDK 原生发现。 */
@ParameterizedClass(autoCloseArguments = false, name = "registry-adapter/{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "hprose")
public class HproseRegistryAdapterTest {
    public static Collection<Object[]> backends() {
        // 每个注册库独立形成 JUnit 实例，不在一个测试方法内循环不同连接方式。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "zookeeper" });
        result.add(new Object[] { "nacos" });
        return result;
    }

    @TempDir
    public Path data;

    private final String backend;

    public HproseRegistryAdapterTest(String backend) {
        this.backend = backend;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesMeteorProviderAndPreservesOrderObject() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "hprose-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();

                // Node 启动没有 URL；resolver 订阅服务后提供完整端点，Java 不代发业务请求。
                Path logs = Files.createDirectory(this.data.resolve("node-consumer"));
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), "hprose"); //
                     NodeHproseOrderPeer consumer = NodeHproseOrderPeer.consumer(logs)) {
                    var discovered = resolver.awaitCount(1);
                    assertEquals("/orders", discovered.get(0).getPath());
                    JsonNode actual = consumer.roundTrip(discovered.get(0), "order");

                    // 官方 SDK 的 save/find 往返验证统一订单对象及查询参数的字段类型。
                    Order expected = OrderMatrixVectors.order("order");
                    assertNodeOrder(expected, actual);
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeProviderAndPreservesOrderObject() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "hprose-consumer");
            outgoing.connector("http") //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();
                assertNull(consumer.getListenAddress("hprose"));
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Node 随机端口真实就绪后再登记，显式标记 node/hprose/http 而非原生注册能力。
                Path logs = Files.createDirectory(this.data.resolve("node-provider"));
                try (NodeHproseOrderPeer provider = NodeHproseOrderPeer.provider(logs); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "hprose", "http", address, "/orders");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    var discovered = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
                    assertEquals(1, discovered.size());
                    assertEquals(address.getPort(), discovered.get(0).getPort());

                    // 无参客户端使用自动地址本，经真实网络验证统一订单对象及查询参数的字段类型。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("order");
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    assertOrder(expected, saved);
                    assertOrder(expected, orders.find(query));
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesMeteorProviderAndPreservesUnicode() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "hprose-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();

                // Node 启动没有 URL；resolver 订阅服务后提供完整端点，Java 不代发业务请求。
                Path logs = Files.createDirectory(this.data.resolve("node-consumer"));
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), "hprose"); //
                     NodeHproseOrderPeer consumer = NodeHproseOrderPeer.consumer(logs)) {
                    var discovered = resolver.awaitCount(1);
                    assertEquals("/orders", discovered.get(0).getPath());
                    JsonNode actual = consumer.roundTrip(discovered.get(0), "unicode-order");

                    // 官方 SDK 的 save/find 往返验证中文、多语种文本和补充字符。
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    assertNodeOrder(expected, actual);
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeProviderAndPreservesUnicode() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "hprose-consumer");
            outgoing.connector("http") //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();
                assertNull(consumer.getListenAddress("hprose"));
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Node 随机端口真实就绪后再登记，显式标记 node/hprose/http 而非原生注册能力。
                Path logs = Files.createDirectory(this.data.resolve("node-provider"));
                try (NodeHproseOrderPeer provider = NodeHproseOrderPeer.provider(logs); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "hprose", "http", address, "/orders");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    var discovered = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
                    assertEquals(1, discovered.size());
                    assertEquals(address.getPort(), discovered.get(0).getPort());

                    // 无参客户端使用自动地址本，经真实网络验证中文、多语种文本和补充字符。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    assertOrder(expected, saved);
                    assertOrder(expected, orders.find(query));
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesMeteorProviderAndPreservesEveryOctet() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "hprose-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();

                // Node 启动没有 URL；resolver 订阅服务后提供完整端点，Java 不代发业务请求。
                Path logs = Files.createDirectory(this.data.resolve("node-consumer"));
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), "hprose"); //
                     NodeHproseOrderPeer consumer = NodeHproseOrderPeer.consumer(logs)) {
                    var discovered = resolver.awaitCount(1);
                    assertEquals("/orders", discovered.get(0).getPath());
                    JsonNode actual = consumer.roundTrip(discovered.get(0), "all-octets");

                    // 官方 SDK 的 save/find 往返验证原生 Buffer 与 Java byte[] 的全部 256 个字节值。
                    Order expected = OrderMatrixVectors.order("all-octets");
                    assertNodeOrder(expected, actual);
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeProviderAndPreservesEveryOctet() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, registryHome)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "hprose-consumer");
            outgoing.connector("http") //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();
                assertNull(consumer.getListenAddress("hprose"));
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Node 随机端口真实就绪后再登记，显式标记 node/hprose/http 而非原生注册能力。
                Path logs = Files.createDirectory(this.data.resolve("node-provider"));
                try (NodeHproseOrderPeer provider = NodeHproseOrderPeer.provider(logs); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "hprose", "http", address, "/orders");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    var discovered = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
                    assertEquals(1, discovered.size());
                    assertEquals(address.getPort(), discovered.get(0).getPort());

                    // 无参客户端使用自动地址本，经真实网络验证原生 Buffer 与 Java byte[] 的全部 256 个字节值。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("all-octets");
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    assertOrder(expected, saved);
                    assertOrder(expected, orders.find(query));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String applicationName) throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName(applicationName);
        configuration.setDataHome(Files.createDirectory(this.data.resolve(applicationName)));
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不开放监听或暴露给 Node。
        return configuration;
    }

    private static void assertNodeOrder(Order expected, JsonNode actual) {
        // 控制 JSON 明确列出对象字段；二进制数组来自 Node 对 Hprose 原生 Buffer 的观察。
        assertTrue(actual.isObject(), "Order must be an object");
        assertEquals(3, actual.size(), "Order field count");
        assertTrue(actual.path("id").isIntegralNumber(), "Order id must be an integer");
        assertEquals(expected.getId(), actual.path("id").intValue());
        assertTrue(actual.path("description").isTextual(), "Order description must be text");
        assertEquals(expected.getDescription(), actual.path("description").textValue());

        JsonNode octets = actual.path("attachment");
        assertTrue(octets.isArray(), "Order attachment must contain octets");
        byte[] actualBytes = new byte[octets.size()];
        for (int index = 0; index < actualBytes.length; index++) {
            JsonNode value = octets.get(index);
            assertTrue(value.isIntegralNumber(), "Attachment octet must be an integer");
            int unsigned = value.intValue();
            assertTrue(unsigned >= 0 && unsigned <= 255, "Attachment octet must fit uint8");
            actualBytes[index] = (byte) unsigned;
        }
        assertArrayEquals(expected.getAttachment(), actualBytes);
    }

    private static void assertOrder(Order expected, Order actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertArrayEquals(expected.getAttachment(), actual.getAttachment());
    }
}
