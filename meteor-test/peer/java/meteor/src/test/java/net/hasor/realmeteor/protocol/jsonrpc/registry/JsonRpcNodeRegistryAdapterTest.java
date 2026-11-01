/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.registry;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** JSON-RPC 的显式 Node 注册适配；业务请求由原生 Jayson SDK 发送，不宣称原生注册发现。 */
@ParameterizedClass(autoCloseArguments = false, name = "jsonrpc/node/adapter/{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "jsonrpc", version = "2.0")
public class JsonRpcNodeRegistryAdapterTest {
    @TempDir
    public Path data;
    private final String          backend;

    public JsonRpcNodeRegistryAdapterTest(String backend) {
        this.backend = backend;
    }
    public static Collection<Object[]> backends() {
        List<Object[]> values = new ArrayList<>();
        values.add(new Object[] { "jdbc" });
        values.add(new Object[] { "nacos" });
        values.add(new Object[] { "zookeeper" });
        return values;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesBasicOrder() throws Exception {
        // Meteor 经公开 bootstrap 接入当前注册库；Node 启动时没有服务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-provider", true);
            try (MetContext provider = configuration.buildContext(); //
                 JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.consumer()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();

                // resolver 只查询和订阅服务端点，业务 RPC 始终由 Node SDK 直连。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "jsonrpc")) {
                    List<URI> discovered = resolver.awaitCount(1);
                    Order expected = OrderMatrixVectors.order("order");
                    Order actual = node.roundTrip(discovered.get(0), expected);

                    // 比较共享订单模型中的字段及原始字节，不用字符串化整个对象代替类型断言。
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesUnicodeOrder() throws Exception {
        // Meteor 经公开 bootstrap 接入当前注册库；Node 启动时没有服务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-provider", true);
            try (MetContext provider = configuration.buildContext(); //
                 JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.consumer()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();

                // resolver 只查询和订阅服务端点，业务 RPC 始终由 Node SDK 直连。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "jsonrpc")) {
                    List<URI> discovered = resolver.awaitCount(1);
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    Order actual = node.roundTrip(discovered.get(0), expected);

                    // 比较共享订单模型中的字段及原始字节，不用字符串化整个对象代替类型断言。
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeConsumerResolvesBinaryOrder() throws Exception {
        // Meteor 经公开 bootstrap 接入当前注册库；Node 启动时没有服务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-provider", true);
            try (MetContext provider = configuration.buildContext(); //
                 JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.consumer()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();

                // resolver 只查询和订阅服务端点，业务 RPC 始终由 Node SDK 直连。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "jsonrpc")) {
                    List<URI> discovered = resolver.awaitCount(1);
                    Order expected = OrderMatrixVectors.order("all-octets");
                    Order actual = node.roundTrip(discovered.get(0), expected);

                    // 比较共享订单模型中的字段及原始字节，不用字符串化整个对象代替类型断言。
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeBasicOrder() throws Exception {
        // Meteor 只有注册库和服务身份，没有预先指定业务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node HTTP server 报告监听就绪后，显式适配器才登记进程与协议端点。
                try (JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = node.awaitAddress(consumer.getSettings().getUnitName());
                    registrar.register(service, node.processId(), "node", "jsonrpc", "http", address, "/orders");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 无固定地址的正式代理读取注册结果，Node provider 保存唯一业务状态。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("order");
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeUnicodeOrder() throws Exception {
        // Meteor 只有注册库和服务身份，没有预先指定业务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node HTTP server 报告监听就绪后，显式适配器才登记进程与协议端点。
                try (JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = node.awaitAddress(consumer.getSettings().getUnitName());
                    registrar.register(service, node.processId(), "node", "jsonrpc", "http", address, "/orders");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 无固定地址的正式代理读取注册结果，Node provider 保存唯一业务状态。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversNodeBinaryOrder() throws Exception {
        // Meteor 只有注册库和服务身份，没有预先指定业务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node HTTP server 报告监听就绪后，显式适配器才登记进程与协议端点。
                try (JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = node.awaitAddress(consumer.getSettings().getUnitName());
                    registrar.register(service, node.processId(), "node", "jsonrpc", "http", address, "/orders");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 无固定地址的正式代理读取注册结果，Node provider 保存唯一业务状态。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = OrderMatrixVectors.order("all-octets");
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void withdrawingNodeRegistrationUpdatesExistingMeteorSubscription() throws Exception {
        // Node 进程全程存活，单独验证服务撤回通知使已有消费者摘除地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "withdrawal-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name("orders").protocol("jsonrpc").aliasName("jsonrpc", "orders").register();
                try (JsonRpcRegistryNodePeer node = JsonRpcRegistryNodePeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = node.awaitAddress(consumer.getSettings().getUnitName());
                    registrar.register(service, node.processId(), "node", "jsonrpc", "http", address, "/orders");
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    Order expected = OrderMatrixVectors.order("order");
                    Order saved = consumer.getRsfClient().getRemote(service).save(expected);
                    assertEquals(expected.getDescription(), saved.getDescription());

                    // 撤回真实登记，等待自动订阅生效，不主动刷新地址或制造连接错误。
                    registrar.withdraw();
                    awaitEmpty(consumer, service);
                    assertTrue(node.isAlive());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean listener) throws Exception {
        // 注册扩展管理 RPC 使用独立 RSF 出口，业务只挂载 JSON-RPC。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.setApplicationName(application).setDataHome(Files.createDirectory(this.data.resolve(application)));
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        var http = configuration.connector("http");
        if (listener) {
            http.bind(ContainerProcess.runnerHost(), 0);
        }
        http.protocol("jsonrpc").addressScheme("http").contextPath("/orders");
        return configuration;
    }

    private static void awaitEmpty(MetContext context, MetBindInfo<?> service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (context.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new TimeoutException("Withdrawn Node provider remains in subscribed address book");
    }
}
