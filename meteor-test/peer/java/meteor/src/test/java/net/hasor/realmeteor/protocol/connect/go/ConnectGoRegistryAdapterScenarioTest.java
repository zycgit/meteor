/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect.go;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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

/** Connect Go 显式注册发现适配；Go SDK 不原生解析 Meteor 注册数据，也不由 Java 代发业务 RPC。 */
@RunWith(Parameterized.class)
public class ConnectGoRegistryAdapterScenarioTest {
    private static final String SERVICE = "meteor.example.OrderDemoService";

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final String tlsMode;

    public ConnectGoRegistryAdapterScenarioTest(String backend, String tlsMode) {
        this.backend = backend;
        this.tlsMode = tlsMode;
    }

    @Parameterized.Parameters(name = "adapter={0},tls={1}")
    public static Collection<Object[]> backends() {
        // 顺序固定为三个明文注册后端，再以 Nacos 验证同一适配链路上的双向认证。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc", "plain" });
        result.add(new Object[] { "zookeeper", "plain" });
        result.add(new Object[] { "nacos", "plain" });
        result.add(new Object[] { "nacos", "mtls" });
        return result;
    }

    @Test(timeout = 180000)
    public void goConsumerResolvesMeteorProviderThroughRegistryAdapter() throws Exception {
        // 每个场景独占注册库和应用目录，Meteor 启动后通过公开入口发布订单接口。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "connect-go-provider-adapter", true);
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE).protocol("connect").aliasName("connect", SERVICE) //
                        .serialize("Protobuf").bindResource("connect", this.resource()).register();

                // 显式适配器从真实存储查询和订阅端点，Go 的目标仅来自解析结果。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "connect")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals(this.scheme(), endpoint.getScheme());
                    String url = endpoint.toString();
                    try (GoPeerProcess peer = GoPeerProcess.start("connect", "client", this.tlsMode, "unicode", url)) {
                        JsonNode result = peer.awaitResult();

                        // 官方 Go 客户端自己完成 save 和 find，并逐字段核对 Unicode 和二进制内容。
                        assertTrue(result.toString(), result.path("ok").asBoolean());
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversGoProviderThroughRegistryAdapter() throws Exception {
        // 消费端配置注册扩展和出站 Connect，订阅不携带任何固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "connect-go-consumer-adapter", false);
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name(SERVICE).protocol("connect").aliasName("connect", SERVICE) //
                        .serialize("Protobuf").bindResource("connect", this.resource()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // Go 报告真实监听成功后，显式 registrar 用独立存储会话登记该进程和协议端点。
                try (GoPeerProcess peer = GoPeerProcess.start("connect", "server", this.tlsMode, "unicode", null); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    URI endpoint = URI.create(peer.awaitReady());
                    assertEquals(this.scheme(), endpoint.getScheme());
                    String host = endpoint.getHost();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress(endpoint.getScheme(), host, endpoint.getPort(), unit);
                    registrar.register(service, peer.processId(), "go", "connect", "http", address, "/");
                    awaitAddress(consumer, service, address);
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 公开代理从自动地址本选址，Go 服务端持有唯一订单状态并实际执行保存和查询。
                    OrderDemoService remote = consumer.getRsfClient().getRemote(service);
                    Order request = new Order();
                    request.setId(101);
                    request.setDescription("订单-你好-🌍-café");
                    request.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
                    Order saved = remote.save(request);
                    OrderQuery query = new OrderQuery();
                    query.setId(request.getId());
                    Order found = remote.find(query);
                    assertEquals(request.getId(), saved.getId());
                    assertEquals(request.getDescription(), saved.getDescription());
                    assertArrayEquals(request.getAttachment(), saved.getAttachment());
                    assertEquals(request.getId(), found.getId());
                    assertEquals(request.getDescription(), found.getDescription());
                    assertArrayEquals(request.getAttachment(), found.getAttachment());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean listening) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect").setDefaultTimeout(10000);
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展内部管理出口，不绑定监听端口。
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", 1);
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        if (StringUtils.equals("mtls", this.tlsMode)) {
            String identity = listening ? "server" : "client";
            connector.option("tlsEnabled", true).option("httpScheme", "https") //
                    .option("tlsTrustStore", "classpath:/grpc/tls/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test").option("tlsAlpn", "http/1.1") //
                    .option("tlsKeyStore", "classpath:/grpc/tls/acceptance/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
            if (listening) {
                connector.option("tlsClientAuth", "REQUIRE");
            }
        }
        connector.protocol("connect").addressScheme(this.scheme()).contextPath("/");
        return configuration;
    }

    private String scheme() {
        return StringUtils.equals("mtls", this.tlsMode) ? "https" : "http";
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
    }

    private static void awaitAddress(MetContext consumer, MetBindInfo<?> service, InterAddress expected) throws Exception {
        // 只观察注册通知维护的公开地址本，不主动同步、写入或直接指定调用目标。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            List<InterAddress> addresses = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses.size() == 1 && addresses.get(0).equals(expected)) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for Go registry address");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Go registry address did not converge: " + expected);
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }
}
