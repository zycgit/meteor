/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.python;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
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
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.jupiter.api.Test;
import net.hasor.realmeteor.environment.container.ContainerProcess;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Python 官方 gRPC SDK 与 Meteor 的注册适配互通，不宣称 SDK 原生理解 Meteor 注册数据。 */
@ParameterizedClass(autoCloseArguments = false, name = "grpc/python/adapter/{0}", quoteTextArguments = false)
@MethodSource("backends")
@NxnRpc(name = "grpc")
public class PythonGrpcRegistryAdapterScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static Collection<Object[]> backends() {
        return List.of(new Object[] { "jdbc" }, new Object[] { "zookeeper" }, new Object[] { "nacos" });
    }

    @TempDir
    public Path data;

    private final String backend;

    public PythonGrpcRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerDiscoversMeteorOrderProvider() throws Exception {
        // Meteor 从公开 bootstrap 启动并发布统一订单接口，注册扩展维护实际协议端点。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-grpc-provider");
            configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("grpc").contextPath("/");
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("grpc") //
                        .aliasName("grpc", "meteor.example.OrderDemoService").bindResource("grpc", resource).register();

                // 显式存储适配器订阅服务 ID，Python SDK 仅取得被发现端点并直接执行网络 RPC。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "grpc")) {
                    URI address = resolver.awaitCount(1).get(0);
                    try (PythonGrpcPeer consumer = PythonGrpcPeer.consumer( //
                            address.getHost(), address.getPort(), "clear", "unicode")) {
                        JsonNode actual = consumer.awaitResult();

                        // Python 同时核对生成消息的 save/find 结果，Java 再核对独立进程报告的业务数据。
                        assertEquals(42, actual.path("id").intValue());
                        assertEquals("Python 订单 🙂 <&>", actual.path("description").textValue());
                        assertEquals("TWV0ZW9y", actual.path("attachment").textValue());
                    }
                }
            }
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerDiscoversPythonOrderProvider() throws Exception {
        // 消费者只配置注册库和出站协议，订阅没有固定业务地址。
        Path home = Files.createDirectory(this.data.resolve("registry"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-grpc-consumer");
            configuration.connector("http").option("httpVersion", 2).protocol("grpc").contextPath("/");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class).name("orders").protocol("grpc") //
                        .aliasName("grpc", "meteor.example.OrderDemoService").bindResource("grpc", resource).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Python 官方 server 就绪后，适配器登记它自己的进程与端点，不代发业务调用。
                try (PythonGrpcPeer provider = PythonGrpcPeer.provider("clear"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = provider.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("grpc", provider.host(), port, unit);
                    registrar.register(service, provider.processId(), "python", "grpc", "http", address, "/");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 统一业务向量经过自动地址本调用真实 Python 存储，保留 Unicode 与二进制附件。
                    Order expected;
                    try (InputStream input = this.getClass().getResourceAsStream("/data/registry/order.json")) {
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

    private MetConfiguration configuration(RegistryConfiguration registry, String application) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setApplicationName(application);
        configuration.setDataHome(Files.createDirectory(this.data.resolve(application)));
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不开放监听，不参与业务发现。
        return configuration;
    }
}
