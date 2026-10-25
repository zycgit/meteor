/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.registry;
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
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Node 官方 Hprose SDK 的显式注册适配，不把辅助进程的存储发现标记为 SDK 原生发现。 */
@RunWith(Parameterized.class)
public class HproseRegistryAdapterTest {
    @Parameterized.Parameters(name = "registry-adapter/{0}")
    public static Collection<Object[]> backends() {
        // 每个注册库独立形成 JUnit 实例，不在一个测试方法内循环不同连接方式。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "zookeeper" });
        result.add(new Object[] { "nacos" });
        return result;
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public HproseRegistryAdapterTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void nodeConsumerResolvesMeteorProviderAndPreservesOrderObject() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-consumer").toPath();
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

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversNodeProviderAndPreservesOrderObject() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-provider").toPath();
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

    @Test(timeout = 180000)
    public void nodeConsumerResolvesMeteorProviderAndPreservesUnicode() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-consumer").toPath();
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

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversNodeProviderAndPreservesUnicode() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-provider").toPath();
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

    @Test(timeout = 180000)
    public void nodeConsumerResolvesMeteorProviderAndPreservesEveryOctet() throws Exception {
        // 注册库与 Meteor 通过正式入口创建；启动后才发布统一订单服务。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-consumer").toPath();
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

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversNodeProviderAndPreservesEveryOctet() throws Exception {
        // 消费者只配置注册库及出站协议，不获得外部进程预先分配的业务地址。
        Path registryHome = this.data.newFolder("registry").toPath();
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
                Path logs = this.data.newFolder("node-provider").toPath();
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
        configuration.setDataHome(this.data.newFolder(applicationName).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不开放监听或暴露给 Node。
        return configuration;
    }

    private static void assertNodeOrder(Order expected, JsonNode actual) {
        // 控制 JSON 明确列出对象字段；二进制数组来自 Node 对 Hprose 原生 Buffer 的观察。
        assertTrue("Order must be an object", actual.isObject());
        assertEquals("Order field count", 3, actual.size());
        assertTrue("Order id must be an integer", actual.path("id").isIntegralNumber());
        assertEquals(expected.getId(), actual.path("id").intValue());
        assertTrue("Order description must be text", actual.path("description").isTextual());
        assertEquals(expected.getDescription(), actual.path("description").textValue());

        JsonNode octets = actual.path("attachment");
        assertTrue("Order attachment must contain octets", octets.isArray());
        byte[] actualBytes = new byte[octets.size()];
        for (int index = 0; index < actualBytes.length; index++) {
            JsonNode value = octets.get(index);
            assertTrue("Attachment octet must be an integer", value.isIntegralNumber());
            int unsigned = value.intValue();
            assertTrue("Attachment octet must fit uint8", unsigned >= 0 && unsigned <= 255);
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
