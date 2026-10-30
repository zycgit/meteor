/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.go;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** grpc-go 的显式注册适配：Java 只处理发现控制数据，业务 RPC 由实际调用方直接发送。 */
@RunWith(Parameterized.class)
public class GrpcGoRegistryAdapterScenarioTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final String tlsMode;

    public GrpcGoRegistryAdapterScenarioTest(String backend, String tlsMode) {
        this.backend = backend;
        this.tlsMode = tlsMode;
    }

    @Parameterized.Parameters(name = "adapter={0},tls={1}")
    public static Object[][] registryModes() {
        return new Object[][] { //
                { "jdbc", "plain" }, //
                { "zookeeper", "plain" }, //
                { "nacos", "plain" }, //
                { "nacos", "mtls" } //
        };
    }

    @Test(timeout = 180000)
    public void providerGoClientResolvesMeteorThroughRegistryAdapter() throws Exception {
        // 独占真实注册存储；Meteor 经 bootstrap 启动后才发布统一订单服务。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, true);
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                        .serialize("Protobuf").bindResource("grpc", this.resource()).register();

                // 地址只能由 StorageAccess 的订阅与目录查询产生，不能直接传递监听地址。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "grpc")) {
                    List<URI> endpoints = resolver.awaitCount(1);
                    URI discovered = endpoints.get(0);
                    assertEquals(this.scheme(), discovered.getScheme());
                    String target = discovered.getHost() + ":" + discovered.getPort();

                    // 官方 grpc-go 进程直接保存并查询订单，mTLS 凭证仍由该进程加载。
                    try (GoPeerProcess peer = GoPeerProcess.start("grpc", "client", this.tlsMode, "unicode", target)) {
                        JsonNode outcome = peer.awaitResult();
                        assertTrue(outcome.toString(), outcome.path("ok").asBoolean());
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void consumerMeteorDiscoversGoThroughRegistryAdapter() throws Exception {
        // 消费端只配置 gRPC 出站和注册扩展，不指定任何远端业务端点。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, false);
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                        .serialize("Protobuf").bindResource("grpc", this.resource()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 独立 Go 服务报告真实端口，显式适配器只登记协议端点和进程身份。
                try (GoPeerProcess peer = GoPeerProcess.start("grpc", "server", this.tlsMode, "unicode", null); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    URI bound = URI.create(this.scheme() + "://" + peer.awaitReady());
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress(this.scheme(), bound.getHost(), bound.getPort(), unit);
                    registrar.register(service, peer.processId(), "go", "grpc", "http", address, "/");
                    this.awaitDiscovery(consumer, service, address);

                    // 自动地址本选择已登记的 Go 实例，Java 代理完成两次真实网络调用。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = this.order();
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order found = orders.find(query);
                    assertEquals(expected.getId(), saved.getId());
                    assertEquals(expected.getDescription(), saved.getDescription());
                    assertArrayEquals(expected.getAttachment(), saved.getAttachment());
                    assertEquals(expected.getId(), found.getId());
                    assertEquals(expected.getDescription(), found.getDescription());
                    assertArrayEquals(expected.getAttachment(), found.getAttachment());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, boolean provider) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc").setDefaultTimeout(10000);
        configuration.setApplicationName(provider ? "go-grpc-adapter-provider" : "go-grpc-adapter-consumer");
        configuration.setDataHome(this.data.newFolder("application").toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展的内部管理出口。
        ConnectorSettings http = configuration.connector("http").option("httpVersion", 2);
        if (provider) {
            http.bind(ContainerProcess.runnerHost(), 0);
        }
        if (StringUtils.equals("mtls", this.tlsMode)) {
            http.option("tlsEnabled", true).option("httpScheme", "https").option("tlsAlpn", "h2") //
                    .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test");
            String identity = provider ? "server.p12" : "client.p12";
            http.option("tlsKeyStore", "classpath:/certs/acceptance/" + identity) //
                    .option("tlsKeyStorePassword", "meteor-test");
            if (provider) {
                http.option("tlsClientAuth", "REQUIRE");
            }
        }
        http.protocol("grpc").addressScheme(this.scheme()).contextPath("/");
        return configuration;
    }

    private String scheme() {
        return StringUtils.equals("plain", this.tlsMode) ? "http" : "https";
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/protocol/proto/order.proto"));
    }

    private Order order() {
        Order order = new Order();
        order.setId(101);
        order.setDescription("订单-你好-🌍-café");
        order.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
        return order;
    }

    private void awaitDiscovery(MetContext context, MetBindInfo<?> service, InterAddress expected) throws Exception {
        // 等待现有订阅更新公开地址本，不手动刷新、更改地址，也不发送探测 RPC。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses.size() == 1) {
                InterAddress actual = addresses.get(0);
                if (actual.getPort() == expected.getPort()) {
                    assertEquals(expected.getHost(), actual.getHost());
                    assertEquals(expected.getSchema(), actual.getSchema());
                    AddressSelectionAwaiter.await(context, service, "save");
                    return;
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for Go provider discovery");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Go gRPC provider not discovered: " + service.getBindID());
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }
}
