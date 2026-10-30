/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect.registry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.matrix.OrderMatrixVectors;
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
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Connect 官方 Node SDK 的显式注册适配，不宣称对端原生理解 Meteor 注册数据。 */
@RunWith(Parameterized.class)
public class ConnectNodeRegistryInteropTest {
    private static final ObjectMapper JSON         = new ObjectMapper();
    private static final String       SERVICE_NAME = "meteor.example.OrderDemoService";

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public ConnectNodeRegistryInteropTest(String backend) {
        this.backend = backend;
    }

    @Parameterized.Parameters(name = "adapter-discovery={0}")
    public static Collection<Object[]> backends() {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "nacos" });
        result.add(new Object[] { "zookeeper" });
        return result;
    }

    @Test(timeout = 180000)
    public void nodeConsumerResolvesMeteorProviderThroughStorageSubscription() throws Exception {
        // 服务先经 bootstrap 启动和发布，再让真实存储订阅解析其 Connect context。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "connect-node-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0).protocol("connect") //
                    .addressScheme("http").contextPath("/connect");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();

                // Node 启动参数没有 URL；订阅返回的端点才通过控制管道送给官方客户端。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver( //
                             storage, service.getBindID(), "connect"); //
                     ConnectOrderRegistryPeer consumer = ConnectOrderRegistryPeer.consumer()) {
                    Order expected = OrderMatrixVectors.order("order");
                    JsonNode request = JSON.valueToTree(expected);
                    JsonNode actual = consumer.roundTrip(resolver.awaitCount(1), request);

                    // 统一订单 POJO 经官方 Node save/find 往返，保留 int32 与普通文本。
                    assertEquals(expected.getId(), actual.path("id").intValue());
                    assertEquals(expected.getDescription(), actual.path("description").textValue());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void nodeConsumerPreservesUnicodeThroughRegistryDiscovery() throws Exception {
        // 服务先经 bootstrap 启动和发布，再让真实存储订阅解析其 Connect context。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "connect-node-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0).protocol("connect") //
                    .addressScheme("http").contextPath("/connect");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();

                // Node 启动参数没有 URL；订阅返回的端点才通过控制管道送给官方客户端。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver( //
                             storage, service.getBindID(), "connect"); //
                     ConnectOrderRegistryPeer consumer = ConnectOrderRegistryPeer.consumer()) {
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    JsonNode request = JSON.valueToTree(expected);
                    JsonNode actual = consumer.roundTrip(resolver.awaitCount(1), request);

                    // Unicode 文本使用同一订单契约，中文与补充平面字符必须逐字保留。
                    assertEquals(expected.getDescription(), actual.path("description").textValue());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void nodeConsumerPreservesBinaryThroughRegistryDiscovery() throws Exception {
        // 服务先经 bootstrap 启动和发布，再让真实存储订阅解析其 Connect context。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "connect-node-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0).protocol("connect") //
                    .addressScheme("http").contextPath("/connect");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();

                // Node 启动参数没有 URL；订阅返回的端点才通过控制管道送给官方客户端。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver( //
                             storage, service.getBindID(), "connect"); //
                     ConnectOrderRegistryPeer consumer = ConnectOrderRegistryPeer.consumer()) {
                    Order expected = OrderMatrixVectors.order("all-octets");
                    JsonNode request = JSON.valueToTree(expected);
                    JsonNode actual = consumer.roundTrip(resolver.awaitCount(1), request);

                    // 业务附件使用 Protobuf bytes，只有控制结果采用 Base64 表达。
                    byte[] actualBytes = Base64.getDecoder().decode(actual.path("attachment").textValue());
                    assertArrayEquals(expected.getAttachment(), actualBytes);
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversRegisteredNodeProvider() throws Exception {
        // 消费者只配置注册库和协议出口，订阅没有固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "connect-node-consumer");
            outgoing.connector("http").protocol("connect").addressScheme("http").contextPath("/connect");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node 服务在动态端口监听成功后，单独的 registrar 才登记真实端点。
                try (ConnectOrderRegistryPeer provider = ConnectOrderRegistryPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "connect", "http", address, "/connect");
                    this.awaitPort(consumer, service, address.getPort());

                    // 接口代理从自动地址本选择 Node，验证基础 POJO 的真实往返。
                    Order expected = OrderMatrixVectors.order("order");
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerPreservesUnicodeThroughRegistryDiscovery() throws Exception {
        // 消费者只配置注册库和协议出口，订阅没有固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "connect-node-consumer");
            outgoing.connector("http").protocol("connect").addressScheme("http").contextPath("/connect");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node 服务在动态端口监听成功后，单独的 registrar 才登记真实端点。
                try (ConnectOrderRegistryPeer provider = ConnectOrderRegistryPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "connect", "http", address, "/connect");
                    this.awaitPort(consumer, service, address.getPort());

                    // Node 持有并回传 Unicode 文本，Java 代理不能丢失中文或补充平面字符。
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getDescription(), actual.getDescription());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerPreservesBinaryThroughRegistryDiscovery() throws Exception {
        // 消费者只配置注册库和协议出口，订阅没有固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "connect-node-consumer");
            outgoing.connector("http").protocol("connect").addressScheme("http").contextPath("/connect");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Node 服务在动态端口监听成功后，单独的 registrar 才登记真实端点。
                try (ConnectOrderRegistryPeer provider = ConnectOrderRegistryPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "connect", "http", address, "/connect");
                    this.awaitPort(consumer, service, address.getPort());

                    // Node 存储真实 Uint8Array，返回到 Java 后逐字节比较附件。
                    Order expected = OrderMatrixVectors.order("all-octets");
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void withdrawingNodeRegistrationRemovesSubscribedAddress() throws Exception {
        // 外部进程在撤回时保持存活，排除连接失败或进程退出带来的假阳性。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "connect-node-withdrawal");
            outgoing.connector("http").protocol("connect").addressScheme("http").contextPath("/connect");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class) //
                        .name(SERVICE_NAME).protocol("connect").aliasName("connect", SERVICE_NAME) //
                        .bindResource("connect", this.proto()).register();
                try (ConnectOrderRegistryPeer provider = ConnectOrderRegistryPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    registrar.register(service, provider.processId(), "node", "connect", "http", address, "/connect");
                    this.awaitPort(consumer, service, address.getPort());
                    Order expected = OrderMatrixVectors.order("order");
                    Order saved = consumer.getRsfClient().getRemote(service).save(expected);
                    assertEquals(expected.getId(), saved.getId());

                    // 只撤回服务登记，现有订阅自行清除地址，不主动刷新或修改 Meteor 地址本。
                    registrar.withdraw();
                    this.awaitPort(consumer, service, 0);
                    assertTrue(provider.isAlive());
                    assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String applicationName) throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setUnitName("local");
        configuration.setApplicationName(applicationName);
        configuration.setDataHome(this.data.newFolder(applicationName).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理 RPC 只配置出口，不开放监听。
        return configuration;
    }

    private GrpcProtoResource proto() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
    }

    private void awaitPort(MetContext context, MetBindInfo<?> service, int expectedPort) throws Exception {
        // 仅观察公开地址和真实选址快照，不通过探测 RPC 或主动同步掩盖自动订阅缺口。
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
        throw new TimeoutException("Subscribed Connect provider did not converge to port " + expectedPort);
    }
}
