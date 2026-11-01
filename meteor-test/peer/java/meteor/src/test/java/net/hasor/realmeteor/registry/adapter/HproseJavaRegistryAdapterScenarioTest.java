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
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 Java Hprose SDK 与 Meteor 双向互通，发现方式明确为公开存储适配。 */
@ParameterizedClass(autoCloseArguments = false, name = "Hprose-java/storage-adapter/{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "hprose")
public class HproseJavaRegistryAdapterScenarioTest {
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

    public HproseJavaRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaConsumerResolvesMeteorProviderThroughStorageAdapter() throws Exception {
        // 通过 bootstrap 启动 Meteor 并发布统一订单接口，注册库保存实际监听端点。
        Path directory = Files.createDirectory(this.data.resolve("hprose-registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
            configuration.addExtension(registry).setApplicationName("hprose-java-adapter-provider");
            configuration.setDataHome(Files.createDirectory(this.data.resolve("hprose-provider-data")));
            configuration.connector("tcp").protocol("rsf"); // 注册扩展的内部管理出口，不开放监听。
            configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();

                // 官方客户端先独立启动，再从存储订阅获得 URL，测试线程不代发业务 RPC。
                Path logs = Files.createDirectory(this.data.resolve("hprose-java-consumer"));
                try (HproseJavaOrderPeerProcess consumer = HproseJavaOrderPeerProcess.consumer(logs); //
                     StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), //
                             "hprose")) {
                    List<URI> discovered = resolver.awaitCount(1);
                    URI target = discovered.get(0);
                    assertEquals("http", target.getScheme());
                    assertEquals("/orders", target.getPath());
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    Order[] actual = consumer.roundTrip(target, expected);

                    // save/find 使用普通 DTO，保留整数、Unicode 和原生 byte[] 的字段语义。
                    assertEquals(2, actual.length);
                    assertOrder(expected, actual[0]);
                    assertOrder(expected, actual[1]);
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversOfficialJavaProviderThroughStorageAdapter() throws Exception {
        // 消费者只配置出站协议和注册库，不监听业务端口，也没有预先指定的业务地址。
        Path directory = Files.createDirectory(this.data.resolve("hprose-registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
            configuration.addExtension(registry).setApplicationName("hprose-java-adapter-consumer");
            configuration.setDataHome(Files.createDirectory(this.data.resolve("hprose-consumer-data")));
            configuration.connector("tcp").protocol("rsf"); // 注册扩展的内部管理出口，不开放监听。
            configuration.connector("http") //
                    .protocol("hprose").addressScheme("http").contextPath("/orders");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name("orders").protocol("hprose").aliasName("Hprose", "orders").register();
                assertNull(consumer.getListenAddress("hprose"));
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方服务引擎报告真实监听端口后，以 java/hprose/http 身份登记存储。
                Path logs = Files.createDirectory(this.data.resolve("hprose-java-provider"));
                try (HproseJavaOrderPeerProcess provider = HproseJavaOrderPeerProcess.provider(logs); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "java", "hprose", "http", address, "/orders");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    List<InterAddress> discovered = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
                    assertEquals(1, discovered.size());
                    assertEquals(address.getPort(), discovered.get(0).getPort());

                    // 无参客户端只通过自动地址本选址，保存与查询都走正式 Hprose 网络通道。
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

    private static void assertOrder(Order expected, Order actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertArrayEquals(expected.getAttachment(), actual.getAttachment());
    }
}
