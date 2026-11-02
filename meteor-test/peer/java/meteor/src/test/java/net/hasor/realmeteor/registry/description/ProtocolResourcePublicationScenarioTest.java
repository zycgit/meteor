/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.description;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.description.MetProtocolDescription;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 资源声明在首次发布前覆盖或清空，最终网络绑定与注册描述必须来自同一份定义。 */
@NxnRpc(name = "grpc")
public class ProtocolResourcePublicationScenarioTest {
    @TempDir
    public Path data;

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void overwrittenProtoDeclarationDrivesBothTheWireAndRegisteredDescription() throws Exception {
        // 同一个普通 Java 接口先绑定原契约，再在首次发布前改用不同的字段编号。
        GrpcProtoResource original = this.originalResource();
        GrpcProtoResource selected = this.replacementResource();
        MetConfiguration incoming = this.providerConfiguration(1);
        MetConfiguration outgoing = this.consumerConfiguration();
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            var publication = provider.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("resource-replacement").protocol("grpc").aliasName("grpc", "example.Echo");
            publication.bindResource("grpc", original);
            publication.bindResource("grpc", selected);
            MetBindInfo<Echo> published = publication.register();
            MetBindInfo<Echo> subscribed = consumer.publisher().rsfService(Echo.class) //
                    .name("resource-replacement").protocol("grpc").aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", selected).register();

            // 两端真实使用新编号；任一端仍使用原编号时，字符串和计数都不能正确往返。
            Echo remote = consumer.getRsfClient(provider.getListenAddress("grpc")).getRemote(subscribed);
            Message result = remote.echo(Message.of("selected definition", 8));
            assertEquals("selected definition", result.getValue());
            assertEquals(9, result.getCount());
            provider.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 管理查询读取存储中的最终端点描述，不能保留原字段号。
            MetServiceDescription description = this.description(provider, published);
            MetProtocolDescription protocol = description.getProtocols().get("grpc").values().iterator().next();
            assertEquals("example.Echo", protocol.getName());
            this.assertMessageFieldIds(protocol, 11, 19);
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void clearedProtoDeclarationUsesTheGeneratedOrderContractInRpcAndRegistration() throws Exception {
        // 原资源描述 Echo；清空后订单服务必须重新按公共注解生成自己的契约。
        MetConfiguration incoming = this.providerConfiguration(1);
        MetConfiguration outgoing = this.consumerConfiguration();
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            var publication = provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("grpc").aliasName("grpc", DirectRpcExample.SERVICE_NAME);
            publication.bindResource("grpc", this.originalResource());
            publication.bindResource("grpc", null);
            MetBindInfo<OrderDemoService> published = publication.register();
            MetBindInfo<OrderDemoService> subscribed = consumer.publisher().rsfService(OrderDemoService.class) //
                    .protocol("grpc").aliasName("grpc", DirectRpcExample.SERVICE_NAME).register();

            // 自动生成的请求与响应经真实 Protobuf 通道往返，而不是走本地提供者。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("grpc")).getRemote(subscribed);
            Order input = new Order();
            input.setId(73);
            input.setDescription("generated after resource clear");
            input.setAttachment(new byte[] { 0, 1, (byte) 255 });
            remote.save(input);
            OrderQuery query = new OrderQuery();
            query.setId(input.getId());
            Order actual = remote.find(query);
            assertEquals(input.getDescription(), actual.getDescription());
            assertArrayEquals(input.getAttachment(), actual.getAttachment());
            provider.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 注册描述保留订单方法与生成类型，不携带已清除的 Echo 契约。
            MetServiceDescription description = this.description(provider, published);
            MetProtocolDescription protocol = description.getProtocols().get("grpc").values().iterator().next();
            assertEquals(DirectRpcExample.SERVICE_NAME, protocol.getName());
            assertEquals(2, protocol.getMethods().size());
            assertFalse(protocol.getTypes().isEmpty());
            assertFalse(protocol.getTypes().containsKey("example.Message"));
            assertEquals(OrderDemoService.class.getName(), description.getJavaType());
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void failedSiblingPublicationKeepsBothLiveEndpointsAndTheirRegisteredSnapshot() throws Exception {
        // 同一个健康服务发布到两个独立 HTTP 端点，预先建立两条真实连接。
        MetConfiguration incoming = this.providerConfiguration(2);
        MetConfiguration outgoing = this.consumerConfiguration();
        GrpcProtoResource resource = this.originalResource();
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<Echo> stable = provider.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("stable-resource").protocol("grpc").aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", resource).register();
            MetBindInfo<Echo> subscribed = consumer.publisher().rsfService(Echo.class) //
                    .name("stable-resource").protocol("grpc").aliasName("grpc", "example.Echo") //
                    .bindResource("grpc", resource).register();
            List<InterAddress> addresses = provider.getListenAddresses("grpc");
            assertEquals(2, addresses.size());
            Echo first = consumer.getRsfClient(addresses.get(0)).getRemote(subscribed);
            Echo second = consumer.getRsfClient(addresses.get(1)).getRemote(subscribed);
            assertEquals(2, first.echo(Message.of("first before", 1)).getCount());
            assertEquals(3, second.echo(Message.of("second before", 2)).getCount());
            provider.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
            MetServiceDescription previous = this.description(provider, stable);
            JsonNode snapshot = new ObjectMapper().valueToTree(previous);

            // 新服务的非法资源让发布回滚，不能污染两个端点上已提交的原服务。
            var rejected = provider.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .name("invalid-resource").protocol("grpc").aliasName("grpc", "example.Invalid") //
                    .bindResource("grpc", new Object());
            assertThrows(IllegalArgumentException.class, rejected::register);
            String group = provider.getSettings().getDefaultGroup();
            String version = provider.getSettings().getDefaultVersion();
            assertNull(provider.getServiceInfo(group, "invalid-resource", version));
            assertEquals(5, first.echo(Message.of("first after", 4)).getCount());
            assertEquals(6, second.echo(Message.of("second after", 5)).getCount());
            provider.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 再次上报后的管理结果与失败前相同，旧查询对象也保持独立快照。
            MetServiceDescription current = this.description(provider, stable);
            JsonNode reported = new ObjectMapper().valueToTree(current);
            assertEquals(snapshot, reported);
            assertEquals(snapshot, new ObjectMapper().valueToTree(previous));
            assertEquals(2, current.getProtocols().get("grpc").size());
            var admin = provider.getExtension(RegistryAdmin.class).getServiceAdmin(stable.getBindID());
            var members = admin.providers(null, null);
            assertEquals(2, members.getItems().get(0).getEndpoints().length);
        }
    }

    private MetConfiguration providerConfiguration(int endpointCount) throws Exception {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("resource-publication-tests");
        registry.setRefreshMillis(60000);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        for (int index = 0; index < endpointCount; index++) {
            configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc").contextPath("/rpc");
        }
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }

    private MetConfiguration consumerConfiguration() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
        configuration.connector("http").option("httpVersion", 2).protocol("grpc").contextPath("/rpc");
        return configuration;
    }

    private GrpcProtoResource originalResource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/protocol/proto/grpc/echo.proto"));
    }

    private GrpcProtoResource replacementResource() throws Exception {
        var contract = Files.createFile(this.data.resolve("replacement.proto"));
        String source = """
                syntax = "proto3";
                package example;
                message Message {
                  string value = 11;
                  int32 count = 19;
                }
                service Echo {
                  rpc echo (Message) returns (Message);
                  rpc fail (Message) returns (Message);
                  rpc slow (Message) returns (Message);
                }
                """;
        Files.writeString(contract, source, StandardCharsets.UTF_8);
        return new GrpcProtoResource(contract.toUri().toURL());
    }

    private MetServiceDescription description(MetContext context, MetBindInfo<?> service) {
        var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());
        return admin.descriptions().get(context.getInstanceID());
    }

    private void assertMessageFieldIds(MetProtocolDescription protocol, int valueId, int countId) {
        Map<?, ?> message = (Map<?, ?>) protocol.getTypes().get("example.Message");
        List<?> fields = (List<?>) message.get("fields");
        assertEquals(2, fields.size());
        Map<?, ?> value = (Map<?, ?>) fields.get(0);
        Map<?, ?> count = (Map<?, ?>) fields.get(1);
        assertEquals("value", value.get("name"));
        assertEquals(valueId, ((Number) value.get("fieldId")).intValue());
        assertEquals("count", count.get("name"));
        assertEquals(countId, ((Number) count.get("fieldId")).intValue());
    }
}
