/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
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

/** Python 官方 Thrift SDK 的显式注册适配场景，编码使用统一订单 IDL 的 Binary/Framed 配置。 */
@RunWith(Parameterized.class)
public class PythonThriftRegistryAdapterScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Parameterized.Parameters(name = "thrift/python/adapter/{0}")
    public static Collection<Object[]> backends() {
        return List.of(new Object[]{"jdbc"}, new Object[]{"zookeeper"}, new Object[]{"nacos"});
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public PythonThriftRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void pythonConsumerDiscoversMeteorOrderProvider() throws Exception {
        // 公开 bootstrap 先启动容器，再发布普通注解接口，业务存储继续复用统一订单实现。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-thrift-provider", true);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                OrderDemoService orders = new OrderDemoServiceImpl();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, orders) //
                        .name("orders").protocol("thrift").aliasName("thrift", "OrderThriftService").register();

                // 注册存储只提供发现地址，实际 Thrift 编码、TCP 与业务请求均由 Python 官方 SDK 完成。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "thrift")) {
                    URI address = resolver.awaitCount(1).get(0);
                    try (PythonThriftPeer consumer = PythonThriftPeer.consumer( //
                            "binary", true, "plain", address.getPort(), "unicode")) {
                        consumer.requireSuccess();
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversPythonOrderProvider() throws Exception {
        // Meteor 只配置出站通道及注册库，不能从固定地址取得 Python 提供者。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-thrift-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class).name("orders") //
                        .protocol("thrift").aliasName("thrift", "OrderThriftService").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 原生 Python server 报告监听成功后登记真实 TCP 端点，并等待自动订阅进入正式选址。
                try (PythonThriftPeer provider = PythonThriftPeer.provider("binary", true, "plain"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = provider.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unit);
                    registrar.register(service, provider.processId(), "python", "thrift", "tcp", address, "/");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 普通订单 DTO 直接送入 Python，不创建生成 DTO 或经过 Java 中继。
                    Order expected;
                    try (InputStream input = this.getClass().getResourceAsStream("/vectors/registry/order.json")) {
                        expected = JSON.readValue(input, Order.class);
                    }
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

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean listen) //
            throws Exception {
        MetConfiguration configuration = PythonThriftSettings.configuration("binary", true, "plain", listen);
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不开放监听，不参与 Thrift 服务发布。
        return configuration;
    }
}
