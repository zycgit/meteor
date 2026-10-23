/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.python;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.realmeteor.environment.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Python 标准库 JSON-RPC 的显式注册适配；不宣称外部对端原生理解 Meteor 注册模型。 */
@RunWith(Parameterized.class)
public class PythonJsonRpcRegistryAdapterScenarioTest {
    @Parameterized.Parameters(name = "jsonrpc/python/adapter/{0}")
    public static Collection<Object[]> backends() {
        return List.of(new Object[] { "jdbc" }, new Object[] { "zookeeper" }, new Object[] { "nacos" });
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final String backend;

    public PythonJsonRpcRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void pythonConsumerDiscoversMeteorOrderProvider() throws Exception {
        // 提供者仅配置自己的注册库，启动后才通过公开 API 发布原有订单接口。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "meteor-jsonrpc-provider");
            incoming.connector("http").bind("127.0.0.1", 0) //
                    .protocol("jsonrpc").addressScheme("http").contextPath("/jsonrpc");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").aliasName("jsonrpc", "orders").protocol("jsonrpc").register();

                // 存储订阅解析出实际端点；不读取提供者监听地址，不从 Java 转发业务请求。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "jsonrpc")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals("http", endpoint.getScheme());
                    assertEquals("/jsonrpc", endpoint.getPath());

                    // Python 标准库直接保存和查询共享向量，并在进程内核对 DTO 与 bytes。
                    String target = endpoint.toString();
                    try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(target, "order")) {
                        consumer.requireSuccess();
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversPythonOrderProvider() throws Exception {
        // Meteor 只持有出站挂载和注册配置，接口订阅没有固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "meteor-jsonrpc-consumer");
            outgoing.connector("http").protocol("jsonrpc").addressScheme("http").contextPath("/jsonrpc");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class).name("orders") //
                        .aliasName("jsonrpc", "orders").protocol("jsonrpc").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 独立进程确认监听后，由显式适配登记其真实端点、协议与进程信息。
                try (PythonJsonRpcOrderPeer provider = PythonJsonRpcOrderPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = provider.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", "127.0.0.1", port, unit);
                    registrar.register(service, provider.processId(), "python", "jsonrpc", "http", address, "/jsonrpc");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 自动地址本选择 Python 服务；Java 代理执行真正的保存和查询网络调用。
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), saved.getId());
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不监听，不参与业务发现。
        return configuration;
    }
}
