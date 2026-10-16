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
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class JsonRpcPublicationTest {
    @Test
    public void unaryServiceCanBePublishedWithoutExtraResources() {
        // 普通接口不需要 IDL、绑定资源或额外的参数注解。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.setBindName("unary");
        JsonRpcCodec.validateService(this.protocol(), service);
    }

    @Test
    public void reservedServiceAliasIsRejectedAtPublication() {
        // 标准保留 rpc. 前缀，业务服务不能占用。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc.internal");
        assertThrows(IllegalArgumentException.class, () -> JsonRpcCodec.validateService(this.protocol(), service));
    }

    @Test
    public void aliasThatGeneratesReservedMethodPrefixIsRejected() {
        // 服务名 rpc 与方法名组合后也会占用保留的 rpc. 前缀。
        ServiceDomain<Unary> service = new ServiceDomain<>(Unary.class);
        service.putAliasName("jsonrpc", "rpc");
        assertThrows(IllegalArgumentException.class, () -> JsonRpcCodec.validateService(this.protocol(), service));
    }

    @Test
    public void streamingReturnIsRejectedAtPublication() {
        // JSON-RPC 单条响应不能承载持续输出的 Publisher。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.setBindName("stream");
        assertThrows(IllegalArgumentException.class, () -> JsonRpcCodec.validateService(this.protocol(), service));
    }

    @Test
    public void streamingParameterIsRejectedAtPublication() {
        // JSON-RPC 参数是完整 JSON 值，不能承载持续输入的 Publisher。
        ServiceDomain<StreamParameter> service = new ServiceDomain<>(StreamParameter.class);
        service.setBindName("stream");
        assertThrows(IllegalArgumentException.class, () -> JsonRpcCodec.validateService(this.protocol(), service));
    }

    @Test
    public void validationDoesNotRestrictServicesPublishedOnlyByOtherProtocols() {
        // 插件只校验暴露给自己的服务，不影响其他协议提供的流能力。
        ServiceDomain<StreamReturn> service = new ServiceDomain<>(StreamReturn.class);
        service.addBindProtocol("grpc");
        JsonRpcCodec.validateService(this.protocol(), service);
    }

    private ProtocolConfig protocol() {
        return ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of("contextPath", "/rpc"));
    }

    public interface Unary {
        String echo(String value);
    }

    public interface StreamReturn {
        Flow.Publisher<String> values();
    }

    public interface StreamParameter {
        String collect(Flow.Publisher<String> values);
    }
}
