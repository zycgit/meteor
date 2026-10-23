/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebProtocolTest {
    @Test
    public void providerDeclaresHttpExchangeTransport() {
        // Web 适配与标准 gRPC 共享 HTTP 传输，不注册新的底层通道。
        HttpProtocolProvider protocol = new GrpcWebProtocol();
        assertEquals("grpc-web", protocol.name());
        assertEquals("http", protocol.transportType());
        assertEquals(HttpExchange.class, protocol.messageType());
        assertEquals("Protobuf", protocol.defaultSerialize());
    }

    @Test
    public void unaryJsonProviderNeedsNoProtoResource() {
        // 使用 JSON 编码时只需要普通 Java 接口。
        ServiceDomain<Unary> service = this.service(Unary.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // Web 协议使用当前配置的默认路径，服务原始声明仍保持未指定。
        ContextPath effective = new ContextPath(Set.of("/web"), false);
        ProtocolBinding binding = new GrpcWebProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
        assertEquals(effective, binding.getContextPath());
        assertEquals("Json", binding.getDescription().path("serializeType").asText());
        assertEquals(1, binding.getDescription().path("methods").size());
        assertNull(meta.getContextPath());
    }

    @Test
    public void serverStreamingProviderIsSupported() {
        // Web 协议允许一个请求产生持续输出消息。
        ServiceDomain<ServerStreaming> service = this.service(ServerStreaming.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new GrpcWebProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void clientStreamingProviderIsRejected() {
        // 浏览器请求体不提供 gRPC 客户端消息流。
        ServiceDomain<ClientStreaming> service = this.service(ClientStreaming.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void bidirectionalProviderIsRejected() {
        // 双向流包含客户端流，不能按单向服务端流发布。
        ServiceDomain<Bidirectional> service = this.service(Bidirectional.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void methodMessageAnnotationIsRejected() {
        // 消息方法缺少结果，不能对应 gRPC-Web 的终止状态。
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void serviceMessageAnnotationIsRejected() {
        // 整个服务声明为消息模式时同样需要阻止发布。
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void configuredMessageModeIsRejected() {
        // 编程式消息标记与注解保持相同语义。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setMessage(true);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void protobufProviderMustBindProtoResource() {
        // 缺少 proto 的 Protobuf 提供者不被当前端点接受。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setSerializeType("Protobuf");
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void mountDefaultEncodingAppliesWhenServiceHasNoSelection() {
        // 服务未选择编码时使用挂载点指定的 JSON。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setSerializeType(null);
        ProtocolConfig config = ProtocolConfig.newConfig("grpc-web", "grpc-web", //
                Map.of("contextPath", "/web", "serializeType", "Json"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new GrpcWebProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void protobufConsumerWithoutAContractIsRejectedDuringPreparation() {
        // 消费者同样需要可编码的契约，缺少文件和字段号时不能准备该协议。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setServiceType(MetServiceType.Consumer);
        service.setSerializeType("Protobuf");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 发布时拒绝，不延迟到首次请求才发现无法编码。
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void voidReturnIsRejected() {
        // 无返回值不是合法 gRPC 响应消息声明。
        ServiceDomain<NoResponse> service = this.service(NoResponse.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void multipleRequestArgumentsAreRejected() {
        // 单条 gRPC 请求必须对应一个消息参数。
        ServiceDomain<MultipleArguments> service = this.service(MultipleArguments.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void preparingAnotherEndpointLeavesExistingBindingsUnchanged() {
        // 协议组已持有其它接入点的发布结果。
        ServiceDomain<Unary> service = this.service(Unary.class);
        ProtocolConfig config = this.config();
        ObjectNode existingDescription = JsonNodeFactory.instance.objectNode();
        ProtocolBinding existing = new ProtocolBinding(null, "existing-definition", existingDescription);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of("existing", existing));

        // 新准备结果不修改已有分组，也不复制其它端点的专属定义。
        ProtocolBinding binding = new GrpcWebProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotSame(existing, binding);
        assertNotNull(binding.getDefinition());
        assertSame(existing, meta.getEndpoint("existing"));
        assertEquals("existing-definition", existing.getDefinition());
        assertSame(existingDescription, existing.getDescription());
        assertNotSame(existingDescription, binding.getDescription());
    }

    @Test
    public void matchingProtoDefinitionAcceptsTheProvider() throws Exception {
        // Java Echo 的全部方法与已绑定 proto 的 unary 过程一致。
        ServiceDomain<Echo> service = this.service(Echo.class);
        service.setSerializeType("Protobuf");
        Object bindResource = this.resource();

        // Web 编码适配沿用同一份服务定义。
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        ProtocolBinding binding = new GrpcWebProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void protoWithoutTheDeclaredServiceRejectsTheProvider() throws Exception {
        // 资源能正确解析，但声明了另一个服务名称。
        ServiceDomain<Echo> service = this.service(Echo.class);
        service.putAliasName("grpc-web", "example.Missing");
        service.setSerializeType("Protobuf");
        Object bindResource = this.resource();

        // 不匹配的绑定不影响其他协议继续发布。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void protoWithoutAJavaMethodRejectsTheProvider() throws Exception {
        // unary 类型声明合法，echo 方法却不存在于 Types proto 中。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("grpc-web", "example.Types");
        service.setSerializeType("Protobuf");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/complex.proto"));

        // 只有方法契约一致的服务才会被端点公开。
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void unaryProtoRejectsAJavaResponseStream() throws Exception {
        // Web 本身允许响应流，但绑定的 echo proto 明确是 unary。
        ServiceDomain<ServerStreaming> service = this.service(ServerStreaming.class);
        service.setSerializeType("Protobuf");
        Object bindResource = this.resource();

        // 不能只依据 Java 返回值忽略 wire 契约的流方向。
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAPathSeparator() {
        // 别名不允许改变协议分配给服务的路径层级。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("grpc-web", "example/Echo");

        // JSON 没有 proto，仍然必须满足地址要求。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAQuery() {
        // 查询部分不能成为服务名的一部分。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("grpc-web", "example.Echo?version=1");

        // 协议拒绝无法正确构造请求路径的服务别名。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    @Test
    public void serviceAliasCannotContainAFragment() {
        // fragment 不属于传输给服务器的 RPC 地址。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("grpc-web", "example.Echo#method");

        // 不能把只存在于客户端 URL 的部分发布为方法路径。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new GrpcWebProtocol().prepareBinding(this.config(), null, meta));
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("grpc-web", "grpc-web", Map.of("contextPath", "/web"));
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName("example.Echo");
        service.putAliasName("grpc-web", "example.Echo");
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Json");
        return service;
    }

    public interface Unary {
        String echo(String request);
    }

    public interface ServerStreaming {
        Flow.Publisher<String> echo(String request);
    }

    public interface ClientStreaming {
        String echo(Flow.Publisher<String> request);
    }

    public interface Bidirectional {
        Flow.Publisher<String> echo(Flow.Publisher<String> request);
    }

    public interface MessageMethod {
        @MetMessage
        String echo(String request);
    }

    @MetMessage
    public interface MessageService {
        String echo(String request);
    }

    public interface NoResponse {
        void echo(String request);
    }

    public interface MultipleArguments {
        String echo(String first, String second);
    }
}
