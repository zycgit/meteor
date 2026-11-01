/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.registry.description;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetServiceEndpoint;
import net.hasor.meteor.registry.admin.description.MetFieldDescription;
import net.hasor.meteor.registry.admin.description.MetProtocolDescription;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 复用订单示例，验证跨协议组合后的注册描述；协议单测不依赖注册中心。 */
@NxnRpc(name = "rsf", version = "1")
@NxnRpc(name = "dubbo")
@NxnRpc(name = "thrift")
@NxnRpc(name = "hprose")
@NxnRpc(name = "soap", version = "1.1")
@NxnRpc(name = "jsonrpc", version = "2.0")
@NxnRpc(name = "xmlrpc")
@NxnRpc(name = "hessian", version = "2")
@NxnRpc(name = "grpc")
@NxnRpc(name = "grpc-web")
@NxnRpc(name = "connect", version = "1")
@NxnRpc(name = "triple")
public class MultiProtocolDescriptionScenarioTest {
    @TempDir
    public Path data;

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nacosStoresTheFullMultiProtocolOrderDescription() throws Exception {
        // 使用宿主已有的独立 Nacos 进程，验证真实 Naming 正文可以保存完整协议描述。
        var home = Files.createDirectory(this.data.resolve("nacos-descriptions"));
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("nacos", home)) {
            var registry = environment.configuration();
            registry.setManagementToken("description-tests");
            registry.setRefreshMillis(60000);
            MetConfiguration config = DirectRpcExample.server("127.0.0.1", 0, 0).addExtension(registry);
            try (MetContext context = config.buildContext()) {
                context.start();
                MetBindInfo<OrderDemoService> service = DirectRpcExample.publish(context, new OrderDemoServiceImpl());
                context.getExtension(RegistryClient.class).pushSync().get(30, TimeUnit.SECONDS);

                // 从注册存储读取的定义保留公共类型和全部实际协议，Protobuf 图没有被截断。
                var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());
                MetServiceDescription description = admin.descriptions().get(context.getInstanceID());
                assertEquals(2, description.getMethods().size());
                assertEquals(DirectRpcExample.PROTOCOLS.size(), description.getProtocols().size());
                assertTrue(description.getTypes().containsKey(Order.class.getName()));
                MetProtocolDescription grpc = description.getProtocols().get("grpc").values().iterator().next();
                assertFalse(grpc.getTypes().isEmpty());
            }
        }
    }

    @Test
    public void orderExampleReportsEveryAcceptedProtocolFromTheSameJavaService() throws Exception {
        // 示例仍只发布一份订单接口，三个 TCP 和九个 HTTP 协议共用各自监听。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration config = DirectRpcExample.server("127.0.0.1", 0, 0).addExtension(registry);
        try (MetContext context = config.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = DirectRpcExample.publish(context, new OrderDemoServiceImpl());
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 公共类型只保存一份，每个已接受协议均具有自己的实际操作描述。
            var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());
            MetServiceDescription description = admin.descriptions().get(context.getInstanceID());
            assertEquals(OrderDemoService.class.getName(), description.getJavaType());
            assertEquals(2, description.getMethods().size());
            assertTrue(description.getTypes().containsKey(Order.class.getName()));
            assertTrue(description.getTypes().containsKey(OrderQuery.class.getName()));
            MetFieldDescription field = description.getTypes().get(Order.class.getName()).getProperties().get(0);
            assertEquals(Long.valueOf(1), field.getFieldId());
            assertEquals("id", field.getName());
            MetFieldDescription parameter = description.getMethods().get(0).getParameters().get(0);
            assertEquals(Long.valueOf(1), parameter.getFieldId());
            assertEquals(Integer.valueOf(0), parameter.getArgIndex());
            Map<String, Map<String, MetProtocolDescription>> protocols = description.getProtocols();
            assertEquals(DirectRpcExample.PROTOCOLS.size(), protocols.size());
            for (String protocol : DirectRpcExample.PROTOCOLS) {
                Map<String, MetProtocolDescription> endpoints = protocols.get(protocol);
                assertEquals(1, endpoints.size(), protocol);
                MetProtocolDescription contract = endpoints.values().iterator().next();
                assertEquals(2, contract.getMethods().size(), protocol);
            }
        }
    }

    @NxnRpc(name = "rsf", version = "1")
    @NxnRpc(name = "jsonrpc", version = "2.0")
    @Test
    public void sameServiceIdKeepsEachInstancesJavaAndProtocolDescriptions() throws Exception {
        // 两个实例共用登记身份和 RSF、JSON-RPC 挂载，各自发布不同的 Java 接口。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration firstConfig = multiProtocolRegistry(registry, "/orders");
        MetConfiguration secondConfig = multiProtocolRegistry(registry, "/echo");
        try (MetContext first = firstConfig.buildContext(); //
             MetContext second = secondConfig.buildContext()) {
            first.start();
            second.start();
            MetBindInfo<OrderDemoService> orders = first.publisher() //
                    .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .group("descriptions").name("shared-contract").version("1") //
                    .protocol("rsf", "jsonrpc").register();
            MetBindInfo<Echo> echo = second.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .group("descriptions").name("shared-contract").version("1") //
                    .protocol("rsf", "jsonrpc").register();
            assertEquals(orders.getBindID(), echo.getBindID());
            first.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
            second.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 同一个目录服务只有登记坐标，完整接口和协议方法分别取自对应实例。
            var admin = first.getExtension(RegistryAdmin.class).getServiceAdmin(orders.getBindID());
            assertEquals("shared-contract", admin.info().getName());
            assertEquals(2, admin.info().getProviderCount());
            Map<String, MetServiceDescription> descriptions = admin.descriptions();
            assertEquals(2, descriptions.size());
            MetServiceDescription orderDescription = descriptions.get(first.getInstanceID());
            MetServiceDescription echoDescription = descriptions.get(second.getInstanceID());
            assertEquals(OrderDemoService.class.getName(), orderDescription.getJavaType());
            assertEquals(Echo.class.getName(), echoDescription.getJavaType());
            assertEquals(2, orderDescription.getMethods().size());
            assertEquals(3, echoDescription.getMethods().size());
            assertTrue(orderDescription.getTypes().containsKey(Order.class.getName()));
            assertFalse(echoDescription.getTypes().containsKey(Order.class.getName()));
            assertEquals(2, orderDescription.getProtocols().size());
            assertEquals(2, echoDescription.getProtocols().size());

            // 成员端点与同实例的协议描述逐一对应，不混用另一实例的接口和路径。
            var members = admin.providers(null, null).getItems();
            assertEquals(2, members.size());
            for (var member : members) {
                String instanceId = member.getInstance().getInstanceId();
                MetServiceDescription description = descriptions.get(instanceId);
                assertEquals(2, member.getEndpoints().length);
                for (MetServiceEndpoint endpoint : member.getEndpoints()) {
                    String protocolName = endpoint.getProtocolName();
                    Map<String, MetProtocolDescription> bindings = description.getProtocols().get(protocolName);
                    MetProtocolDescription protocol = bindings.get(endpoint.getConnectorName());
                    assertNotNull(protocol);
                    assertEquals(description.getMethods().size(), protocol.getMethods().size());
                    assertNotNull(endpoint.getPublishedAddress());
                    if (StringUtils.equals("jsonrpc", protocolName)) {
                        boolean orderInstance = StringUtils.equals(first.getInstanceID(), instanceId);
                        String expectedPath = orderInstance ? "/orders" : "/echo";
                        assertEquals(expectedPath, endpoint.getContextPath());
                    }
                }
            }
        }
    }

    @NxnRpc(name = "grpc")
    @Test
    public void twoGrpcEndpointsKeepDifferentJsonAndProtobufDefinitions() throws Exception {
        // 同名协议两个端点独立选择编码与挂载目录。
        MetConfiguration config = new MetConfiguration().addExtension(registry());
        config.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/json").option("serializeType", "Json");
        config.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/protobuf").option("serializeType", "Protobuf");
        config.connector("tcp").protocol("rsf");
        try (MetContext context = config.buildContext()) {
            context.start();
            MetBindInfo<OrderDemoService> service = context.publisher() //
                    .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .aliasName("grpc", DirectRpcExample.SERVICE_NAME).protocol("grpc").register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 两个描述分别来自本端点绑定，不能用首个协议配置覆盖第二个端点。
            var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());
            MetServiceDescription description = admin.descriptions().get(context.getInstanceID());
            Map<String, MetProtocolDescription> endpoints = description.getProtocols().get("grpc");
            List<ConnectorConfig> connectors = List.copyOf(context.getSettings().getConnectorConfigs());
            MetProtocolDescription json = endpoints.get(connectors.get(0).name());
            MetProtocolDescription protobuf = endpoints.get(connectors.get(1).name());
            assertEquals(DirectRpcExample.SERVICE_NAME, json.getName());
            assertEquals(DirectRpcExample.SERVICE_NAME, protobuf.getName());
            assertEquals("Json", json.getSerializeType());
            assertTrue(json.getTypes().isEmpty());
            assertEquals("Protobuf", protobuf.getSerializeType());
            assertFalse(protobuf.getTypes().isEmpty());
            var members = admin.providers(null, null).getItems();
            assertEquals(2, members.get(0).getEndpoints().length);
            assertEquals("grpc", members.get(0).getEndpoints()[0].getProtocolName());
            assertEquals("grpc", members.get(0).getEndpoints()[1].getProtocolName());
            assertEquals("/json", members.get(0).getEndpoints()[0].getContextPath());
            assertEquals("/protobuf", members.get(0).getEndpoints()[1].getContextPath());
        }
    }

    @NxnRpc(name = "grpc")
    @Test
    public void rejectedProtobufEndpointDoesNotAppearInRegistrationDescription() throws Exception {
        // 普通未编号 DTO 可以使用 JSON，但不能隐式生成 Protobuf 字段号。
        MetConfiguration config = new MetConfiguration().addExtension(registry());
        config.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/json").option("serializeType", "Json");
        config.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/protobuf").option("serializeType", "Protobuf");
        config.connector("tcp").protocol("rsf");
        try (MetContext context = config.buildContext()) {
            context.start();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").protocol("grpc").register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 注册记录只包含实际接受服务的 JSON 端点及其有效路径。
            var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());
            MetServiceDescription description = admin.descriptions().get(context.getInstanceID());
            assertEquals(1, description.getProtocols().get("grpc").size());
            var endpoints = admin.providers(null, null).getItems().get(0).getEndpoints();
            assertEquals(1, endpoints.length);
            assertEquals("/json", endpoints[0].getContextPath());
        }
    }

    private static MemoryRegistryConfiguration registry() {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("description-tests");
        registry.setRefreshMillis(60000);
        return registry;
    }

    private static MetConfiguration multiProtocolRegistry(MemoryRegistryConfiguration registry, String contextPath) {
        MetConfiguration config = new MetConfiguration().addExtension(registry);
        config.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        config.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc").contextPath(contextPath);
        return config;
    }
}
