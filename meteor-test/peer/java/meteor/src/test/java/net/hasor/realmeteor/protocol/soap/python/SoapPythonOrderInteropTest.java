/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.python;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.matrix.OrderDiscovery;
import net.hasor.realmeteor.matrix.OrderRegistryEnvironment;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.Assert.*;

/** 独立 Python SOAP SDK 的双向订单矩阵，注册模式明确使用存储发现和登记适配。 */
@RunWith(Parameterized.class)
public class SoapPythonOrderInteropTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ClassRule
    public static final TemporaryFolder          serverData = new TemporaryFolder();
    private static      OrderRegistryEnvironment environment;

    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final OrderDiscovery  discovery;

    public SoapPythonOrderInteropTest(OrderDiscovery discovery) {
        this.discovery = discovery;
    }

    @Parameterized.Parameters(name = "soap/python/{0}")
    public static Collection<Object[]> configurations() {
        List<Object[]> configurations = new ArrayList<>();
        for (OrderDiscovery discovery : OrderDiscovery.selected()) {
            configurations.add(new Object[] { discovery });
        }
        return configurations;
    }

    @BeforeClass
    public static void createEnvironment() {
        environment = new OrderRegistryEnvironment(serverData.getRoot().toPath());
    }

    @AfterClass
    public static void closeEnvironment() throws Exception {
        if (environment != null) {
            environment.close();
        }
    }

    @Test(timeout = 180000)
    public void pythonZeepConsumerSavesAndFindsOrdersFromMeteorGeneratedWsdl() throws Exception {
        // Meteor 从 bootstrap 启动；业务接口不绑定手写 WSDL，Python 消费者启动时没有 URL。
        String scope = "soap-zeep-" + UUID.randomUUID();
        MetConfiguration incoming = this.configuration(scope, true);
        try {
            RegistryConfiguration registry = this.registry(scope);
            if (registry != null) {
                incoming.addExtension(registry);
            }
            try (MetContext provider = incoming.buildContext(); //
                 PythonSoapPeer consumer = PythonSoapPeer.consumer()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("soap").aliasName("soap", "orders").register();
                JsonNode expected = this.vector();
                JsonNode actual;

                // 直连读取监听地址；注册模式只从公开存储订阅取得协议挂载端点。
                if (registry == null) {
                    InterAddress listen = provider.getListenAddress("soap");
                    URI mount = new URI("http", null, listen.getHost(), listen.getPort(), "/soap", null, null);
                    actual = consumer.roundTrip(this.wsdl(mount), expected);
                } else {
                    try (StorageAccess storage = registry.openStorage(); //
                         RegistryAddressResolver resolver = new RegistryAddressResolver( //
                                 storage, service.getBindID(), "soap")) {
                        URI mount = resolver.awaitCount(1).get(0);
                        actual = consumer.roundTrip(this.wsdl(mount), expected);
                    }
                }

                // Zeep 完成 save/find 并返回 JSON 控制结果，字段类型和原始字节都必须一致。
                assertTrue(actual.path("id").isIntegralNumber());
                assertTrue(actual.path("description").isTextual());
                assertEquals(expected.path("id").intValue(), actual.path("id").intValue());
                assertEquals(expected.path("description").textValue(), actual.path("description").textValue());
                byte[] expectedBytes = Base64.getDecoder().decode(expected.path("attachment").textValue());
                byte[] actualBytes = Base64.getDecoder().decode(actual.path("attachment").textValue());
                assertArrayEquals(expectedBytes, actualBytes);
            }
        } finally {
            environment.release(scope);
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerSavesAndFindsOrdersFromPythonSoapfishProvider() throws Exception {
        // soapfish2 持有唯一 Python 订单存储，Java 绑定该 SDK 自动生成的本地契约。
        String scope = "soap-soapfish-" + UUID.randomUUID();
        MetConfiguration outgoing = this.configuration(scope, false);
        try {
            RegistryConfiguration registry = this.registry(scope);
            if (registry != null) {
                outgoing.addExtension(registry);
            }
            try (PythonSoapPeer provider = PythonSoapPeer.provider(); //
                 MetContext consumer = outgoing.buildContext(); //
                 ForeignServiceRegistrar registrar = registry == null ? null : new ForeignServiceRegistrar(registry)) {
                consumer.start();
                var contract = Path.of(System.getProperty("python.soap.contract")).toUri().toURL();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class).name("orders") //
                        .protocol("soap").aliasName("soap", "orders") //
                        .bindResource("soap", new SoapWsdlResource(contract)).register();
                MetClient client;

                // 注册模式向存储登记真实 Python 进程，消费者只使用自动发现的地址本。
                if (registrar == null) {
                    client = consumer.getRsfClient(provider.address(consumer.getSettings().getUnitName()));
                } else {
                    InterAddress address = provider.address(consumer.getSettings().getUnitName());
                    long processId = provider.processId();
                    registrar.register(service, processId, "python", "soap", "http", address, "/soap/orders");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "save");
                    client = consumer.getRsfClient();
                }

                // 普通 Meteor 接口代理直接调用 Python SOAP SDK，不经过 Java 业务代理。
                Order expected = JSON.treeToValue(this.vector(), Order.class);
                OrderDemoService remote = client.getRemote(service);
                remote.save(expected);
                OrderQuery query = new OrderQuery();
                query.setId(expected.getId());
                Order actual = remote.find(query);
                assertEquals(expected.getId(), actual.getId());
                assertEquals(expected.getDescription(), actual.getDescription());
                assertArrayEquals(expected.getAttachment(), actual.getAttachment());
            }
        } finally {
            environment.release(scope);
        }
    }

    private MetConfiguration configuration(String scope, boolean listen) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.setDefaultGroup(scope).setApplicationName("python-soap-interop");
        configuration.setDataHome(this.data.newFolder("application").toPath());
        var http = configuration.connector("http");
        if (listen) {
            http.bind(ContainerProcess.runnerHost(), 0);
        }
        http.protocol("soap").contextPath("/soap").addressScheme("http");

        // 只有注册扩展安装内部管理出口；业务仍只向 SOAP 挂载公开。
        if (this.discovery != OrderDiscovery.DIRECT) {
            var management = configuration.connector("tcp");
            if (listen) {
                management.bind(ContainerProcess.runnerHost(), 0);
            }
            management.protocol("rsf");
        }
        return configuration;
    }

    private RegistryConfiguration registry(String scope) throws Exception {
        if (this.discovery == OrderDiscovery.DIRECT) {
            return null;
        }
        return environment.configuration(this.discovery, scope);
    }

    private URI wsdl(URI mount) throws Exception {
        // SOAP 服务别名是协议路径的一部分；挂载地址及端口始终来自直连配置或发现结果。
        return new URI(mount.getScheme(), null, mount.getHost(), mount.getPort(), //
                mount.getPath() + "/orders", "wsdl", null);
    }

    private JsonNode vector() throws Exception {
        try (InputStream input = this.getClass().getResourceAsStream("/vectors/registry/order.json")) {
            return JSON.readTree(input);
        }
    }
}
