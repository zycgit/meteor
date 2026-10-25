/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.go;
import java.net.URI;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import net.hasor.realmeteor.matrix.OrderDiscovery;
import net.hasor.realmeteor.matrix.OrderRegistryEnvironment;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Thrift Go 显式注册适配，仅验证 Binary/Framed/Plain；Go SDK 不原生读取 Meteor 注册数据。 */
@RunWith(Parameterized.class)
public class ThriftGoRegistryAdapterScenarioTest {
    @ClassRule
    public static final TemporaryFolder          environmentData = new TemporaryFolder();
    private static      OrderRegistryEnvironment environment;

    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final OrderDiscovery  discovery;

    public ThriftGoRegistryAdapterScenarioTest(OrderDiscovery discovery) {
        this.discovery = discovery;
    }

    @Parameterized.Parameters(name = "thrift/go/adapter/{0}/binary-framed/plain")
    public static Object[][] discoveries() {
        return new Object[][] { // 每个注册后端分别报告两个调用方向，不包含直连或其他安全配置。
                { OrderDiscovery.JDBC }, //
                { OrderDiscovery.ZOOKEEPER }, //
                { OrderDiscovery.NACOS } //
        };
    }

    @BeforeClass
    public static void createEnvironment() {
        environment = new OrderRegistryEnvironment(environmentData.getRoot().toPath());
    }

    @AfterClass
    public static void closeEnvironment() throws Exception {
        if (environment != null) {
            environment.close();
        }
    }

    @Test(timeout = 180000)
    public void goConsumerResolvesMeteorProviderThroughRegistryAdapter() throws Exception {
        // 每个方法独占注册空间和缓存；外部存储进程由测试类共享并统一关闭。
        String scope = UUID.randomUUID().toString();
        try {
            RegistryConfiguration registry = environment.configuration(this.discovery, scope);
            MetConfiguration incoming = this.configuration(registry, scope, true);
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<?> service = OrderProtocol.THRIFT.publish(provider, new OrderDemoServiceImpl());

                // Go 的目标只能来自存储查询与订阅，不读取或传递 Meteor 的监听地址。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "thrift")) {
                    URI discovered = resolver.awaitCount(1).get(0);
                    assertEquals("thrift", discovered.getScheme());
                    String target = discovered.getAuthority();
                    try (GoPeerProcess peer = GoPeerProcess.start( //
                            "thrift-binary-framed", "client", "plain", "unicode", target)) {
                        JsonNode result = peer.awaitResult();

                        // 官方 Go SDK 自己保存并查询 Unicode 订单，Java 适配器只处理发现信息。
                        assertTrue(result.toString(), result.path("ok").asBoolean());
                    }
                }
            }
        } finally {
            environment.release(scope);
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversGoProviderThroughRegistryAdapter() throws Exception {
        // Meteor 只配置 Thrift 与内部管理出站能力，初始订阅没有固定业务地址。
        String scope = UUID.randomUUID().toString();
        try {
            RegistryConfiguration registry = environment.configuration(this.discovery, scope);
            MetConfiguration outgoing = this.configuration(registry, scope, false);
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<?> service = OrderProtocol.THRIFT.subscribe(consumer);
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());
                assertNull(consumer.getListenAddress("thrift"));

                // 官方 Go 服务报告实际监听成功后，独立存储会话登记其进程与真实端点。
                try (GoPeerProcess peer = GoPeerProcess.start( //
                        "thrift-binary-framed", "server", "plain", "unicode", null); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    String target = peer.awaitReady();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("thrift://" + target + "/" + unit);
                    registrar.register(service, peer.processId(), "go", "thrift", "tcp", address, "/");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 公开客户端从自动地址本选址；业务 RPC 直接到 Go，不经过发现适配器。
                    var addresses = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
                    assertEquals(1, addresses.size());
                    assertEquals(address, addresses.get(0));
                    MetClient remote = consumer.getRsfClient();
                    Order request = OrderMatrixVectors.order("unicode-order");
                    Order saved = OrderProtocol.THRIFT.save(remote, service, request);
                    Order found = OrderProtocol.THRIFT.find(remote, service, request.getId());

                    // 保存响应和查询结果分别保留统一订单向量中的全部字段。
                    assertEquals(request.getId(), saved.getId());
                    assertEquals(request.getDescription(), saved.getDescription());
                    assertArrayEquals(request.getAttachment(), saved.getAttachment());
                    assertEquals(request.getId(), found.getId());
                    assertEquals(request.getDescription(), found.getDescription());
                    assertArrayEquals(request.getAttachment(), found.getAttachment());
                }
            }
        } finally {
            environment.release(scope);
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String scope, boolean provider) throws Exception {
        // 订单绑定继续复用示例；只选择 Binary/Framed 明文配置，不扩展本例的矩阵范围。
        MetConfiguration configuration = provider ? OrderProtocol.THRIFT.providerConfiguration() //
                : OrderProtocol.THRIFT.consumerConfiguration();
        if (provider) {
            configuration.getConnectors().get(0).bind(ContainerProcess.runnerHost(), 0);
        }
        configuration.setDefaultGroup(scope);
        String application = provider ? "thrift-go-provider-adapter" : "thrift-go-consumer-adapter";
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        var thrift = configuration.getConnectors().get(0).getProtocols().get(0);
        thrift.option("encoding", "binary").option("framed", true).addressScheme("thrift");

        // 注册扩展管理服务只使用 RSF；提供端开启管理监听，消费端只声明出口。
        var management = configuration.connector("tcp");
        if (provider) {
            management.bind(ContainerProcess.runnerHost(), 0);
        }
        management.protocol("rsf");
        return configuration;
    }
}
