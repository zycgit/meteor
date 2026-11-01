/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.adapter;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import net.hasor.realmeteor.environment.discovery.PythonOrderPeer;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Timeout;

/** 外语显式注册适配矩阵；不宣称 Python SDK 原生理解 Meteor 注册数据。 */
@ParameterizedClass(autoCloseArguments = false, name = "{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "xmlrpc")
public class RegistryForeignAdapterScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static Collection<Object[]> backends() {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "zookeeper" });
        result.add(new Object[] { "nacos" });
        return result;
    }

    @TempDir
    public Path data;

    private final String backend;

    public RegistryForeignAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerResolvesMeteorProviderThroughPublicStorage() throws Exception {
        // 注册库独占；Meteor 提供者经 bootstrap 启动后才发布统一订单接口。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "java-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0).protocol("xmlrpc").contextPath("/xmlrpc");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("xmlrpc").aliasName("xmlrpc", "orders").register();

                // Python 启动时没有 URL；显式 resolver 按服务 ID 订阅后提供真实端点与 context path。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "xmlrpc"); //
                     PythonOrderPeer consumer = PythonOrderPeer.consumer()) {
                    JsonNode expected = vector();
                    JsonNode actual = consumer.roundTrip(resolver.awaitCount(1), expected);

                    // save 和 find 都由 Python 标准库直接发往 Meteor，没有 Java HTTP 代发。
                    assertOrder(expected, actual);
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversRegisteredPythonProvider() throws Exception {
        // Java 只配置出站 XML-RPC 与注册库，订阅中没有固定远端业务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "java-consumer");
            outgoing.connector("http").protocol("xmlrpc").contextPath("/xmlrpc");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("xmlrpc").aliasName("xmlrpc", "orders").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Python 报告实际监听成功后，registrar 通过公开存储 API 登记其真实进程与端点。
                try (PythonOrderPeer provider = PythonOrderPeer.provider(0); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), address, "/xmlrpc");
                    awaitPort(consumer, service, address.getPort());

                    // Meteor 接口代理使用自动地址本，Python 服务器持有唯一业务状态。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    JsonNode expected = vector();
                    Order order = JSON.treeToValue(expected, Order.class);
                    orders.save(order);
                    OrderQuery query = new OrderQuery();
                    query.setId(order.getId());
                    assertOrder(expected, JSON.valueToTree(orders.find(query)));
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void withdrawingForeignRegistrationRemovesSubscribedAddress() throws Exception {
        // 本场景只改变外部服务登记，Python 进程继续存活以排除连接失败造成的假阳性。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "withdrawal-consumer");
            outgoing.connector("http").protocol("xmlrpc").contextPath("/xmlrpc");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("xmlrpc").aliasName("xmlrpc", "orders").register();
                try (PythonOrderPeer provider = PythonOrderPeer.provider(0); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), address, "/xmlrpc");
                    awaitPort(consumer, service, address.getPort());
                    Order expected = JSON.treeToValue(vector(), Order.class);
                    Order saved = consumer.getRsfClient().getRemote(service).save(expected);
                    assertOrder(vector(), JSON.valueToTree(saved));

                    // 撤销单个服务触发现有订阅；测试不主动刷新或删除 Meteor 地址。
                    registrar.withdraw();
                    awaitPort(consumer, service, 0);
                    assertTrue(provider.isAlive());
                    assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void restartedPythonProviderUpdatesExistingMeteorSubscription() throws Exception {
        // 消费者与代理只创建一次；先验证旧进程可用，再通过登记会话关闭撤回旧成员。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "restart-consumer");
            outgoing.connector("http").protocol("xmlrpc").contextPath("/xmlrpc");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name("orders").protocol("xmlrpc").aliasName("xmlrpc", "orders").register();
                OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                Order expected = JSON.treeToValue(vector(), Order.class);
                int previousPort;
                try (PythonOrderPeer first = PythonOrderPeer.provider(0); //
                     ForeignServiceRegistrar registration = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = first.address(consumer.getSettings().getUnitName());
                    previousPort = address.getPort();
                    registration.register(service, first.processId(), address, "/xmlrpc");
                    awaitPort(consumer, service, previousPort);
                    assertOrder(vector(), JSON.valueToTree(orders.save(expected)));
                }
                awaitPort(consumer, service, 0);

                // 新进程强制选择不同端口，原订阅及原代理须通过新通知自动恢复。
                try (PythonOrderPeer second = PythonOrderPeer.provider(previousPort); //
                     ForeignServiceRegistrar registration = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = second.address(consumer.getSettings().getUnitName());
                    assertNotEquals(previousPort, address.getPort());
                    registration.register(service, second.processId(), address, "/xmlrpc");
                    awaitPort(consumer, service, address.getPort());
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    assertOrder(vector(), JSON.valueToTree(orders.find(query)));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String applicationName) throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName(applicationName);
        configuration.setDataHome(Files.createDirectory(this.data.resolve(applicationName)));
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展的内部管理出口，不开放监听。
        return configuration;
    }

    private static JsonNode vector() throws Exception {
        try (InputStream input = RegistryForeignAdapterScenarioTest.class.getResourceAsStream( //
                "/data/registry/order.json")) {
            return JSON.readTree(input);
        }
    }

    private static void assertOrder(JsonNode expected, JsonNode actual) throws Exception {
        // Java 对象树中的 byte[] 是 BinaryNode，Python JSON 结果中的同一字段是 Base64 文本。
        assertTrue(actual.isObject(), "Order must be an object");
        assertEquals(expected.size(), actual.size(), "Order fields");
        JsonNode id = actual.path("id");
        assertTrue(id.isIntegralNumber(), "Order id must be an integer");
        assertTrue(id.canConvertToInt(), "Order id must fit int32");
        assertEquals(expected.path("id").intValue(), id.intValue());

        // 文本按业务内容精确比较，不能通过整树字符串掩盖类型或字段差异。
        JsonNode description = actual.path("description");
        assertTrue(description.isTextual(), "Order description must be text");
        assertEquals(expected.path("description").textValue(), description.textValue());

        JsonNode attachment = actual.path("attachment");
        byte[] bytes;
        if (attachment.isBinary()) {
            bytes = attachment.binaryValue();
        } else {
            assertTrue(attachment.isTextual(), "Order attachment must be binary or Base64 text");
            bytes = Base64.getDecoder().decode(attachment.textValue());
        }
        byte[] expectedBytes = Base64.getDecoder().decode(expected.path("attachment").textValue());
        assertArrayEquals(expectedBytes, bytes, "Order attachment bytes");
    }

    private static void awaitPort(MetContext context, MetBindInfo<?> service, int expectedPort) throws Exception {
        // 只观察公开地址本；expectedPort 为零时等待所有提供者撤回，不修改被测地址。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
            boolean empty = expectedPort == 0 && addresses.isEmpty();
            boolean single = expectedPort > 0 && addresses.size() == 1;
            if (empty || single && addresses.get(0).getPort() == expectedPort) {
                if (expectedPort > 0) {
                    AddressSelectionAwaiter.await(context, service, "save");
                }
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new TimeoutException("Subscribed provider did not converge to port " + expectedPort);
    }
}
