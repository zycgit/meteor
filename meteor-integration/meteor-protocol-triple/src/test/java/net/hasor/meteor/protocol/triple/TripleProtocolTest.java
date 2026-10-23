/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple;
import java.io.InputStream;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Flow;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 入口提供者的注册身份与发布期契约。 */
public class TripleProtocolTest {
    @Test
    public void providerDeclaresTripleOverExistingHttpTransport() {
        // Triple 不复制传输栈，默认使用普通 Java 接口的 Hessian2 wrapper。
        TripleProtocol protocol = new TripleProtocol();
        assertEquals("triple", protocol.name());
        assertEquals("http", protocol.transportType());
        assertEquals("Hessian2", protocol.defaultSerialize());
        assertEquals(HttpExchange.class, protocol.messageType());
    }

    @Test
    public void serviceLoaderDiscoversTheRootProvider() {
        // 按最终产物使用的 SPI 入口发现扩展，而不是指定实现类名。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(TripleProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("triple", provider.name());
        assertTrue(provider instanceof HttpProtocolProvider);
    }

    @Test
    public void ordinaryJavaProviderNeedsNoProtoResource() {
        // 多参数、重载和 void 都属于普通接口 wrapper 的合法方法。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // Wrapper 定义与当前端点的默认路径在同一次准备中完成绑定。
        ContextPath effective = new ContextPath(Set.of("/triple"), false);
        ProtocolBinding binding = new TripleProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
        assertEquals(effective, binding.getContextPath());
        assertEquals("Hessian2", binding.getDescription().path("serializeType").asText());
        assertEquals(8, binding.getDescription().path("methods").size());
        assertNull(meta.getContextPath());
    }

    @Test
    public void ordinaryJsonProviderNeedsNoProtoResource() {
        // 选择 JSON 的普通接口不绑定 Protobuf 描述文件。
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        service.setSerializeType("Json");
        Map<String, String> options = Map.of("contextPath", "/triple", "httpMode", "json");
        ProtocolConfig json = ProtocolConfig.newConfig("triple", "triple", options);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(new TripleProtocol().prepareBinding(json, null, meta));
    }

    @Test
    public void allDirectFlowShapesCanBePublishedWithWrapperEncoding() {
        // 同一个 Java 接口可以声明请求流、响应流和双向流。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void protobufProviderMustBindProtoFile() {
        // Protobuf 在发布阶段校验资源，不延迟到首个网络请求。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setSerializeType("Protobuf");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void protobufProviderAcceptsItsDeclaredServiceAndMethods() throws Exception {
        // 使用插件已有的公开 proto 资源，将描述绑定到发布的 Java 接口。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setSerializeType("Protobuf");
        service.putAliasName("triple", "meteor.triple.demo.TripleFlowService");
        Object bindResource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));

        // 服务及各个方法的流式方向与描述一致时允许发布。
        ContextPath effective = new ContextPath(Set.of("/native"), false);
        ProtocolMeta meta = new ProtocolMeta(service, effective, bindResource, Map.of());
        ProtocolBinding binding = new TripleProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertSame(effective, binding.getContextPath());
        String serviceName = "meteor.triple.demo.TripleFlowService";
        assertEquals(serviceName, binding.getDescription().path("name").asText());
        assertEquals(9, binding.getDescription().path("methods").size());
        assertTrue(binding.getDescription().path("types").size() > 0);
        assertSame(effective, meta.getContextPath());
    }

    @Test
    public void protobufConsumerWithoutAContractIsRejectedDuringPreparation() {
        // 消费者缺少文件和字段号时无法准备 Protobuf 编码。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setServiceType(MetServiceType.Consumer);
        service.setSerializeType("Protobuf");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void serviceWithoutAnEncodingUsesTheMountDefault() {
        // 未显式指定编码的服务继承挂载点的 JSON 配置。
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        service.setSerializeType(null);
        ProtocolConfig config = ProtocolConfig.newConfig("triple", "triple", //
                Map.of("contextPath", "/triple", "serializeType", "Json", "httpMode", "json"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void unknownProviderEncodingIsRejected() {
        // 不把不存在的编码静默回退到 Hessian2。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        service.setSerializeType("Missing");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void methodMessageAnnotationCannotRemoveTripleTerminationStatus() {
        // Triple 的普通调用必须带终止状态，消息方法不能借 void 规则放行。
        ProtocolConfig config = this.config();
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void messageServiceAnnotationIsRejected() {
        // 接口级消息标记对整个服务生效。
        ProtocolConfig config = this.config();
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void programmaticMessageModeIsRejected() {
        // 编程式消息标记与注解保持同一协议约束。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        service.setMessage(true);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void providerMustDeclareATripleAlias() {
        // 协议调用路径由公开别名确定，缺失别名不能进入已发布状态。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleDemoService> service = new ServiceDomain<>(TripleDemoService.class);
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Hessian2");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void providerAliasCannotInjectAnAdditionalPath() {
        // 别名只表示服务名称，不得向协议路径注入目录。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        service.putAliasName("triple", "example.Service/other");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void plainHttpCannotPublishFlowMethods() {
        // 普通 HTTP 分支只支持 unary，不把流式方法静默降为单值调用。
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setSerializeType("Json");
        Map<String, String> options = Map.of("contextPath", "/triple", "httpMode", "json");
        ProtocolConfig config = ProtocolConfig.newConfig("triple", "triple", options);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void nativeJsonUsesTheBoundProtoMethodDefinitions() throws Exception {
        // 原生 JSON 使用 proto 的方法与消息定义，而不是 POJO wrapper。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setSerializeType("Json");
        service.putAliasName("triple", "meteor.triple.demo.TripleFlowService");
        Object bindResource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNotNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void nativeAliasMustExistInTheBoundProto() throws Exception {
        // 不存在的 proto 服务名不能依赖第一次请求才报错。
        ProtocolConfig config = this.config();
        ServiceDomain<TripleFlowService> service = this.service(TripleFlowService.class);
        service.setSerializeType("Protobuf");
        Object bindResource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void nativeFlowDirectionMustMatchItsProtoMethod() throws Exception {
        // proto 的 count 是响应流，Java 声明不能把它发布成普通结果。
        ProtocolConfig config = this.config();
        ServiceDomain<WrongFlowDirection> service = this.service(WrongFlowDirection.class);
        service.setSerializeType("Protobuf");
        service.putAliasName("triple", "meteor.triple.demo.TripleFlowService");
        Object bindResource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void wrapperCannotPublishIndistinguishableFlowAndUnarySignatures() {
        // 请求流会在线上携带元素类型，不能与同名普通参数方法共享该签名。
        ProtocolConfig config = this.config();
        ServiceDomain<AmbiguousWireSignature> service = this.service(AmbiguousWireSignature.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void wrapperAllowsDistinctFlowAndUnaryWireSignatures() {
        // 同名方法的线上参数类型不同，可由 wrapper 明确选中。
        ProtocolConfig config = this.config();
        ServiceDomain<DistinctWireSignature> service = this.service(DistinctWireSignature.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNotNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void rawRequestFlowDoesNotExposeAProtocolEndpoint() {
        // 原始 Publisher 没有解码元素类型，提供者拒绝端点而不抛启动异常。
        ProtocolConfig config = this.config();
        ServiceDomain<RawFlowRequest> service = this.service(RawFlowRequest.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void rawResponseFlowDoesNotExposeAProtocolEndpoint() {
        // 响应原始 Publisher 同样不能建立稳定的消息契约。
        ProtocolConfig config = this.config();
        ServiceDomain<RawFlowResponse> service = this.service(RawFlowResponse.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void blockingInputStreamDoesNotExposeAProtocolEndpoint() {
        // 阻塞 I/O 流不属于 Triple 消息参数，服务仍可由其它协议或本地接入。
        ProtocolConfig config = this.config();
        ServiceDomain<BlockingInput> service = this.service(BlockingInput.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new TripleProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void acceptedEndpointsBuildIndependentBindings() {
        // 同一协议的不同端点使用各自配置，不复用另一个端点的绑定。
        ProtocolConfig first = this.config();
        ProtocolConfig second = ProtocolConfig.newConfig("triple", "triple-secure", //
                Map.of("contextPath", "/secure"));
        ServiceDomain<TripleDemoService> service = this.service(TripleDemoService.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        TripleProtocol protocol = new TripleProtocol();
        ProtocolBinding firstBinding = protocol.prepareBinding(first, null, meta);
        ProtocolBinding secondBinding = protocol.prepareBinding(second, null, meta);

        // 两个结果各自保存方法名称表，准备新端点不会修改已有定义。
        assertNotNull(firstBinding);
        assertNotNull(secondBinding);
        assertNotSame(firstBinding, secondBinding);
        assertNotNull(firstBinding.getDefinition());
        assertNotNull(secondBinding.getDefinition());
        assertNotSame(firstBinding.getDefinition(), secondBinding.getDefinition());
        assertNotSame(firstBinding.getDescription(), secondBinding.getDescription());
        assertEquals(Set.of("/triple"), firstBinding.getContextPath().getPaths());
        assertEquals(Set.of("/secure"), secondBinding.getContextPath().getPaths());
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("triple", "triple", Map.of("contextPath", "/triple"));
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("triple", type.getName());
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Hessian2");
        return service;
    }

    public interface RawFlowRequest {
        @SuppressWarnings("rawtypes")
        String upload(Flow.Publisher source);
    }

    public interface RawFlowResponse {
        @SuppressWarnings("rawtypes")
        Flow.Publisher download();
    }

    public interface BlockingInput {
        String upload(InputStream source);
    }

    public interface WrongFlowDirection {
        TripleMessage count(TripleMessage request);
    }

    public interface AmbiguousWireSignature {
        String echo(String request);

        String echo(Flow.Publisher<String> request);
    }

    public interface DistinctWireSignature {
        String echo(int request);

        String echo(Flow.Publisher<String> request);
    }

    public interface MessageMethod {
        @MetMessage
        void send(String value);
    }

    @MetMessage
    public interface MessageService {
        void send(String value);
    }
}
