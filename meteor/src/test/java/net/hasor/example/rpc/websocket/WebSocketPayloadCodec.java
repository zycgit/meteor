/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import java.lang.reflect.Method;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.serialize.SerializeCoder;

/** 仅示范应用协议如何映射 Payload，不接触 Caller 或 Dispatcher 内部实现。 */
public final class WebSocketPayloadCodec {
    private final MetContext     context;
    private final ProtocolConfig config;
    private final SerializeCoder json;

    public WebSocketPayloadCodec(MetContext context, ProtocolConfig config) {
        this.context = context;
        this.config = config;
        this.json = context.getSerializeCoder("Json");
    }

    public byte[] encode(WebSocketEnvelope envelope) throws Exception {
        return this.json.encode(envelope);
    }

    public WebSocketEnvelope decode(byte[] bytes) throws Exception {
        return (WebSocketEnvelope) this.json.decode(bytes, WebSocketEnvelope.class);
    }

    public WebSocketEnvelope request(RequestPayload request) throws Exception {
        WebSocketEnvelope envelope = new WebSocketEnvelope();
        envelope.type = "request";
        envelope.id = request.getRequestID();
        envelope.group = request.getServiceGroup();
        envelope.name = request.getServiceName();
        envelope.version = request.getServiceVersion();
        envelope.method = request.getTargetMethod();
        envelope.parameterTypes.addAll(request.getParameterTypes());
        envelope.timeout = request.getClientTimeout();
        envelope.flags = request.getFlags();
        envelope.message = request.isMessage();
        for (Object argument : request.getParameterValues()) {
            envelope.arguments.add(this.json.encode(argument));
        }
        return envelope;
    }

    public RequestPayload request(WebSocketEnvelope envelope, RouteMount mount) throws Exception {
        // 服务与方法从正式发布目录解析，不能通过另一个 context 调用未暴露的服务。
        MetBindInfo<?> service = this.context.getServiceInfo(envelope.group, envelope.name, envelope.version);
        boolean provider = service != null && service.getServiceType() == MetServiceType.Provider;
        if (!provider || !this.config.accepts(service, mount)) {
            throw new IllegalArgumentException("Service is not published on this WebSocket context");
        }
        Class<?>[] parameterTypes = this.parameterTypes(envelope.parameterTypes);
        Method method = MetRuntimeUtils.getServiceMethod(service.getBindType(), envelope.method, parameterTypes);
        if (parameterTypes.length != envelope.arguments.size()) {
            throw new IllegalArgumentException("JSON argument count does not match the published method");
        }

        // 只构建正式请求负载，由协议会话交还核心调度链。
        RequestPayload request = new RequestPayload();
        request.setRequestID(envelope.id);
        request.setReceiveTime(System.currentTimeMillis());
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType("Json");
        request.setClientTimeout(envelope.timeout);
        request.setFlags(envelope.flags);
        request.setMessage(envelope.message);
        for (int index = 0; index < parameterTypes.length; index++) {
            Object value = this.json.decode(envelope.arguments.get(index), parameterTypes[index]);
            request.addParameter(envelope.parameterTypes.get(index), value);
        }
        return request;
    }

    public WebSocketEnvelope response(ResponsePayload response) throws Exception {
        WebSocketEnvelope envelope = new WebSocketEnvelope();
        envelope.type = "response";
        envelope.id = response.getRequestID();
        envelope.status = response.getStatus();
        envelope.returnType = response.getReturnType();
        envelope.result = this.json.encode(response.getReturnData());
        if (response.getFailure() != null) {
            envelope.returnType = String.class.getName();
            envelope.result = this.json.encode(response.getFailure().toString());
        }
        return envelope;
    }

    public ResponsePayload response(WebSocketEnvelope envelope) throws Exception {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(envelope.id);
        response.setReceiveTime(System.currentTimeMillis());
        response.setStatus(envelope.status);
        response.setSerializeType("Json");
        response.setReturnType(envelope.returnType);
        if (envelope.returnType != null) {
            Class<?> returnType = MetRuntimeUtils.getType(envelope.returnType, this.context.getClassLoader());
            if (returnType != void.class) {
                response.setReturnData(this.json.decode(envelope.result, returnType));
            }
        }
        return response;
    }

    private Class<?>[] parameterTypes(List<String> names) {
        Class<?>[] types = new Class<?>[names.size()];
        for (int index = 0; index < types.length; index++) {
            types[index] = MetRuntimeUtils.getType(names.get(index), this.context.getClassLoader());
        }
        return types;
    }
}
