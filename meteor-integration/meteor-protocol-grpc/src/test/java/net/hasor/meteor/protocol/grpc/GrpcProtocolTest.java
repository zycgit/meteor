/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Messages;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GrpcProtocolTest {
    @Test
    public void matchingProtoDefinitionAcceptsTheProvider() throws Exception {
        // Java 方法名称和 unary 方向与已有 Echo proto 一致。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Echo");
        Object bindResource = this.resource();

        // 正式协议校验结合当前配置，生成包含默认路径的静态绑定。
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", Map.of("contextPath", "/grpc"));
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        ContextPath effective = new ContextPath(Set.of("/grpc"), false);
        HttpProtocolProvider provider = new GrpcProtocol();
        ProtocolBinding binding = provider.prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
        assertEquals(effective, binding.getContextPath());
        assertEquals("example.Echo", binding.getDescription().path("name").asText());
        assertTrue(binding.getDescription().path("types").isObject());
        assertFalse(binding.getDescription().path("contextExact").asBoolean());
        assertNull(meta.getContextPath());
    }

    @Test
    public void preparingAnotherEndpointLeavesExistingBindingsUnchanged() throws Exception {
        // 协议组已经保存另一接入点的结果，本次准备不修改已发布分组。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Echo");
        Object bindResource = this.resource();
        ProtocolConfig config = this.config();
        ObjectNode existingDescription = JsonNodeFactory.instance.objectNode();
        ProtocolBinding existing = new ProtocolBinding(null, "existing-definition", existingDescription);
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of("existing", existing));

        // 返回独立接入结果，包含静态映射，也不覆盖另一接入点的结果。
        ProtocolBinding binding = new GrpcProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotSame(existing, binding);
        assertNotNull(binding.getDefinition());
        assertSame(existing, meta.getEndpoint("existing"));
        assertEquals("existing-definition", existing.getDefinition());
        assertSame(existingDescription, existing.getDescription());
        assertNotSame(existingDescription, binding.getDescription());
    }

    @Test
    public void missingProtoRejectsProvider() {
        // Protobuf 提供者具有合法别名，但没有绑定描述资源。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Echo");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 当前端点不接受缺少 wire 定义的服务。
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void unsupportedMultipleArgumentMethodRejectsProvider() {
        // JSON 排除缺少 proto 的影响，单独验证双参数方法的支持边界。
        ServiceDomain<GrpcWebProtocolTest.MultipleArguments> service = //
                this.service(GrpcWebProtocolTest.MultipleArguments.class, "example.Echo");
        service.setSerializeType("Json");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // gRPC 过程只接受一条请求消息，拒绝无法对应的方法签名。
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void protoWithoutTheDeclaredServiceRejectsTheProvider() throws Exception {
        // 已提供的 proto 合法，但没有服务声明所指定的名称。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Missing");
        Object bindResource = this.resource();

        // 不支持的发布返回 null，不作为资源解析异常抛出。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void protoWithoutAJavaMethodRejectsTheProvider() throws Exception {
        // Echo proto 不包含 Messages 接口的 list、collect 等方法。
        ServiceDomain<Messages> service = this.service(Messages.class, "example.Echo");
        Object bindResource = this.resource();

        // 缺失过程使当前协议无法承载整份 Java 服务。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void unaryProtoRejectsAJavaRequestStream() throws Exception {
        // proto 的 echo 是 unary，Java 却声明客户端请求流。
        ServiceDomain<GrpcWebProtocolTest.ClientStreaming> service = //
                this.service(GrpcWebProtocolTest.ClientStreaming.class, "example.Echo");
        Object bindResource = this.resource();

        // 双方方向不一致时，不能把流服务按普通消息公开。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void unaryProtoRejectsAJavaResponseStream() throws Exception {
        // proto 的 echo 是 unary，Java 却声明服务端响应流。
        ServiceDomain<GrpcWebProtocolTest.ServerStreaming> service = //
                this.service(GrpcWebProtocolTest.ServerStreaming.class, "example.Echo");
        Object bindResource = this.resource();

        // 响应方向和请求方向分别属于发布契约。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAPathSeparator() {
        // 别名自身不能注入路径层级，JSON 服务也遵守同一寻址要求。
        ServiceDomain<Echo> service = this.service(Echo.class, "example/Echo");
        service.setSerializeType("Json");

        // 路由无法唯一表达的别名不被当前挂载接受。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAQuery() {
        // 查询字符串属于 HTTP 地址，不属于服务名称。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Echo?version=1");
        service.setSerializeType("Json");

        // 非法地址字符不会混入发布结果。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAFragment() {
        // URL fragment 不能成为 RPC 服务路径的一部分。
        ServiceDomain<Echo> service = this.service(Echo.class, "example.Echo#method");
        service.setSerializeType("Json");

        // 不依赖首次调用才发现路径无法发送。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void sameProtocolKeepsEachEndpointBindingAndEffectiveContext() throws Exception {
        // 同一个协议挂到两个端点，各端点拥有独立地址前缀和默认目录。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("grpc") //
                .addressScheme("grpc-primary").contextPath("/primary");
        configuration.connector("http").protocol("grpc") //
                .addressScheme("grpc-secondary").contextPath("/secondary");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            Iterator<ConnectorConfig> endpoints = context.getSettings().getConnectorConfigs().iterator();
            ConnectorConfig primary = endpoints.next();
            ConnectorConfig secondary = endpoints.next();

            // 服务域保存原始声明和接纳结果，默认目录仅属于对应端点的绑定。
            ProtocolMeta grpc = MetBindInfoWrap.unwrap(service).getProtocolMeta("grpc");
            assertNotNull(grpc);
            assertNull(grpc.getContextPath());
            assertEquals(Set.of(primary.name(), secondary.name()), grpc.getEndpoints().keySet());
            ProtocolBinding primaryBinding = grpc.getEndpoint(primary.name());
            ProtocolBinding secondaryBinding = grpc.getEndpoint(secondary.name());
            assertEquals("grpc-primary", primary.protocol("grpc").scheme());
            assertEquals("grpc-secondary", secondary.protocol("grpc").scheme());
            assertEquals(Set.of("/primary"), primaryBinding.getContextPath().getPaths());
            assertEquals(Set.of("/secondary"), secondaryBinding.getContextPath().getPaths());
            assertNotNull(primaryBinding.getDefinition());
            assertNotNull(secondaryBinding.getDefinition());
        }
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("grpc", "grpc", Map.of());
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
    }

    private <T> ServiceDomain<T> service(Class<T> type, String alias) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setServiceType(MetServiceType.Provider);
        service.putAliasName("grpc", alias);
        service.setSerializeType("Protobuf");
        return service;
    }
}
