/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.util.Map;
import java.util.concurrent.Flow;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcPublicationTest {
    @Test
    public void unaryServiceCanBePublishedWithoutExtraResources() {
        // 普通接口不需要 IDL、绑定资源或额外的参数注解。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.setBindName("unary");
        assertTrue(JsonRpcCodec.validateService(service));
    }

    @Test
    public void reservedServiceAliasIsRejectedAtPublication() {
        // 标准保留 rpc. 前缀，业务服务不能占用。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc.internal");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void aliasThatGeneratesReservedMethodPrefixIsRejected() {
        // 服务名 rpc 与方法名组合后也会占用保留的 rpc. 前缀。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void sameArityOverloadsAreRejectedAtPublication() {
        // 线上请求只有方法名和参数数量，参数类型不同不能区分入口。
        ServiceDomain<SameArity> service = new ServiceDomain<>(SameArity.class);
        service.setBindName("overloaded");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void distinctArityOverloadsRemainSupported() {
        // 不同参数数量能在调用前确定唯一方法，继续允许发布。
        ServiceDomain<DistinctArity> service = new ServiceDomain<>(DistinctArity.class);
        service.setBindName("overloaded");
        assertTrue(JsonRpcCodec.validateService(service));
    }

    @Test
    public void streamingReturnIsRejectedAtPublication() {
        // JSON-RPC 单条响应不能承载持续输出的 Publisher。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.setBindName("stream");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void streamingParameterIsRejectedAtPublication() {
        // JSON-RPC 参数是完整 JSON 值，不能承载持续输入的 Publisher。
        ServiceDomain<StreamParameter> service = new ServiceDomain<>(StreamParameter.class);
        service.setBindName("stream");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void unsupportedShapeIsRejectedRegardlessOfProtocolDeclaration() {
        // 能力检查不受协议声明干扰，是否调用检查由发布阶段决定。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.addBindProtocol("grpc");
        assertFalse(JsonRpcCodec.validateService(service));
    }

    @Test
    public void providerReturnsAcceptedBindingForSupportedService() {
        // 普通接口不需要额外协议定义，返回当前接入的地址和配置。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.setBindName("unary");
        ProtocolConfig config = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of());
        ProtocolMeta metadata = new ProtocolMeta(service, null, Map.of());
        ProtocolBinding binding = new JsonRpcProtocol().validate(config, metadata, null);
        assertNotNull(binding);
        assertEquals("jsonrpc", binding.getScheme());
        assertNull(binding.getDefinition());
    }

    @Test
    public void providerDeclinesUnsupportedServiceWithoutFailingPublication() {
        // 协议不支持持续输出时返回拒绝结果，交由发布阶段继续检查其他协议。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.setBindName("stream");
        ProtocolConfig config = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of());
        ProtocolMeta metadata = new ProtocolMeta(service, null, Map.of());
        ProtocolBinding binding = new JsonRpcProtocol().validate(config, metadata, null);
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
}
