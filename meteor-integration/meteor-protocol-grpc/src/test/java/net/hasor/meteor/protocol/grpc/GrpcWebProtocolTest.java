/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Map;
import java.util.concurrent.Flow;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebProtocolTest {
    @Test
    public void factoryDeclaresHttpExchangeTransport() {
        // Web 适配与标准 gRPC 共享 HTTP 传输，不注册新的底层通道。
        GrpcWebProtocol protocol = new GrpcWebProtocol();
        assertEquals("grpc-web", protocol.name());
        assertEquals("http", protocol.transportType());
        assertEquals(HttpExchange.class, protocol.messageType());
        assertEquals("Protobuf", protocol.defaultSerializeType());
    }

    @Test
    public void protocolUsesMountedHttpRoutingRatherThanByteProbe() {
        // HTTP 请求通过上下文路径路由，工厂不参与 TCP 字节探测。
        HttpRequest request = HttpRequest.newBuffered("POST", "/web/example.Echo/echo", Map.of(), //
                new byte[0], 1, Map.of());
        RouteProbeResult result = new GrpcWebProtocol().probe(this.config(), new HttpExchange(request));
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void unaryJsonProviderNeedsNoProtoResource() {
        // 使用 JSON 编码时只需要普通 Java 接口。
        ServiceDomain<Unary> service = this.service(Unary.class);
        new GrpcWebProtocol().validateService(this.config(), service);
    }

    @Test
    public void serverStreamingProviderIsSupported() {
        // Web 协议允许一个请求产生持续输出消息。
        ServiceDomain<ServerStreaming> service = this.service(ServerStreaming.class);
        new GrpcWebProtocol().validateService(this.config(), service);
    }

    @Test
    public void clientStreamingProviderIsRejected() {
        // 浏览器请求体不提供 gRPC 客户端消息流。
        ServiceDomain<ClientStreaming> service = this.service(ClientStreaming.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void bidirectionalProviderIsRejected() {
        // 双向流包含客户端流，不能按单向服务端流发布。
        ServiceDomain<Bidirectional> service = this.service(Bidirectional.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void methodMessageAnnotationIsRejected() {
        // 消息方法缺少结果，不能对应 gRPC-Web 的终止状态。
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void serviceMessageAnnotationIsRejected() {
        // 整个服务声明为消息模式时同样需要阻止发布。
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void configuredMessageModeIsRejected() {
        // 编程式消息标记与注解保持相同语义。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setMessage(true);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void protobufProviderMustBindProtoResource() {
        // 默认 Protobuf 不能等到第一次调用才发现缺少描述文件。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setSerializeType("Protobuf");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
        assertEquals("Protobuf service must bind proto file.", failure.getMessage());
    }

    @Test
    public void mountDefaultEncodingAppliesWhenServiceHasNoSelection() {
        // 服务未选择编码时使用挂载点指定的 JSON。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setSerializeType(null);
        ProtocolConfig config = ProtocolConfig.newConfig("grpc-web", "grpc-web", //
                Map.of("contextPath", "/web", "serializeType", "Json"));
        new GrpcWebProtocol().validateService(config, service);
    }

    @Test
    public void protobufConsumerDeclarationDoesNotPublishService() {
        // 仅订阅的描述无需被当作本地 Protobuf 提供者校验。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setServiceType(MetServiceType.Consumer);
        service.setSerializeType("Protobuf");
        new GrpcWebProtocol().validateService(this.config(), service);
    }

    @Test
    public void voidReturnIsRejected() {
        // 无返回值不是合法 gRPC 响应消息声明。
        ServiceDomain<NoResponse> service = this.service(NoResponse.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
    }

    @Test
    public void multipleRequestArgumentsAreRejected() {
        // 单条 gRPC 请求必须对应一个消息参数。
        ServiceDomain<MultipleArguments> service = this.service(MultipleArguments.class);
        assertThrows(IllegalArgumentException.class, //
                () -> new GrpcWebProtocol().validateService(this.config(), service));
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
