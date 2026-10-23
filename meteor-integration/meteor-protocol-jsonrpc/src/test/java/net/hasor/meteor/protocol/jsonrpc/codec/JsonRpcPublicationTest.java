/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcPublicationTest {
    @Test
    public void inheritedGenericTypesAndNamesArePreparedTogether() throws Exception {
        // Java 方法继承 T，服务接口将其绑定到 String。
        ServiceDomain<TextService> service = new ServiceDomain<>(TextService.class);
        service.setBindName("text");
        JsonRpcServiceBinding binding = JsonRpcServiceBinding.prepare(service);
        Method method = TextService.class.getMethod("repeat", Object.class);

        assertEquals("echo", binding.name(method));
        assertEquals("message", binding.parameterName(method, 0));
        assertSame(String.class, binding.parameterType(method, 0).resolve());
        assertSame(String.class, binding.returnType(method).resolve());
    }

    @Test
    public void unaryServiceCanBePublishedWithoutExtraResources() {
        // 普通接口不需要 IDL、绑定资源或额外的参数注解。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.setBindName("unary");
        assertNotNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void reservedServiceAliasIsRejectedAtPublication() {
        // 标准保留 rpc. 前缀，业务服务不能占用。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc.internal");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void aliasThatGeneratesReservedMethodPrefixIsRejected() {
        // 服务名 rpc 与方法名组合后也会占用保留的 rpc. 前缀。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void sameArityOverloadsAreRejectedAtPublication() {
        // 线上请求只有方法名和参数数量，参数类型不同不能区分入口。
        ServiceDomain<SameArity> service = new ServiceDomain<>(SameArity.class);
        service.setBindName("overloaded");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void distinctArityOverloadsRemainSupported() {
        // 不同参数数量能在调用前确定唯一方法，继续允许发布。
        ServiceDomain<DistinctArity> service = new ServiceDomain<>(DistinctArity.class);
        service.setBindName("overloaded");
        assertNotNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void streamingReturnIsRejectedAtPublication() {
        // JSON-RPC 单条响应不能承载持续输出的 Publisher。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.setBindName("stream");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void streamingParameterIsRejectedAtPublication() {
        // JSON-RPC 参数是完整 JSON 值，不能承载持续输入的 Publisher。
        ServiceDomain<StreamParameter> service = new ServiceDomain<>(StreamParameter.class);
        service.setBindName("stream");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void unsupportedShapeIsRejectedRegardlessOfProtocolDeclaration() {
        // 能力检查不受协议声明干扰，是否调用检查由发布阶段决定。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.addBindProtocol("grpc");
        assertNull(JsonRpcServiceBinding.prepare(service));
    }

    @Test
    public void providerReturnsAcceptedBindingForSupportedService() {
        // 普通接口无需契约文件，发布检查准备当前接入的 Java 方法映射。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.setBindName("unary");
        ProtocolConfig config = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of());
        ProtocolMeta metadata = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new JsonRpcProtocol().prepareBinding(config, null, metadata);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void providerPreparesTheGenericContractDescriptionTogetherWithTheBinding() throws Exception {
        // 公共泛型描述和命名参数沿用同一次协议定义准备结果。
        ServiceDomain<TextService> service = new ServiceDomain<>(TextService.class);
        service.setBindName("text");
        ProtocolConfig config = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of());
        ProtocolMeta metadata = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new JsonRpcProtocol().prepareBinding(config, null, metadata);

        // 定义中的线上名称、参数名和实际类型与随绑定返回的描述一致。
        JsonRpcServiceBinding definition = (JsonRpcServiceBinding) binding.getDefinition();
        Method method = TextService.class.getMethod("repeat", Object.class);
        JsonNode operation = binding.getDescription().path("methods").get(0);
        JsonNode parameter = operation.path("parameters").get(0);
        assertEquals(definition.name(method), operation.path("name").asText());
        assertEquals(definition.parameterName(method, 0), parameter.path("name").asText());
        assertEquals(definition.parameterType(method, 0).toString(), parameter.path("type").asText());
        assertEquals(0, parameter.path("argIndex").asInt());
    }

    @Test
    public void providerDeclinesUnsupportedServiceWithoutFailingPublication() {
        // 协议不支持持续输出时返回拒绝结果，交由发布阶段继续检查其他协议。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.setBindName("stream");
        ProtocolConfig config = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of());
        ProtocolMeta metadata = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new JsonRpcProtocol().prepareBinding(config, null, metadata);
        assertNull(binding);
    }

    public interface Unary {
        String echo(String value);
    }

    public interface SameArity {
        int select(int value);

        int select(String value);
    }

    public interface DistinctArity {
        int select(int value);

        int select(int first, int second);
    }

    public interface StreamReturn {
        Flow.Publisher<String> values();
    }

    public interface StreamParameter {
        String collect(Flow.Publisher<String> values);
    }

    public interface GenericService<T> {
        @MetMethod(name = "echo")
        T repeat(@MetParam(name = "message") T value);
    }

    public interface TextService extends GenericService<String> {
    }

}
