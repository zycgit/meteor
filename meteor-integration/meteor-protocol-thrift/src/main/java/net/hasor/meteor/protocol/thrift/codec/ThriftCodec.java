/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.InvocationTargetException;
import net.hasor.cobble.BeanUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryInputTransport;

/** 把协议发布定义映射到 Meteor 请求和响应；正文由统一的值编解码器处理。 */
public final class ThriftCodec {
    private final ProtocolConfig  protocol;
    private final RouteMountIndex routes;
    private final MetContext      context;
    private final ThriftWireCodec wire;
    private final String          encoding;
    private final boolean         multiplexed;
    private final int             maxMessageSize;
    private final int             maxDepth;

    public ThriftCodec(ProtocolConfig protocol, RouteMountIndex routes, MetContext context) {
        this.protocol = protocol;
        this.routes = routes;
        this.context = context;
        this.encoding = protocol.option("encoding", "binary");
        validateOptions(protocol);
        this.multiplexed = booleanOption(protocol, "multiplexed", false);
        this.maxMessageSize = protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        this.maxDepth = protocol.integer("maxDepth", 64);
        int maxContainerSize = protocol.integer("maxContainerSize", 100000);
        this.wire = new ThriftWireCodec(this.maxMessageSize, maxContainerSize, this.maxDepth);
    }

    public byte[] encodeRequest(RequestPayload request, int sequenceId) throws TException {
        validateSerialization(request.getSerializeType());
        MetBindInfo<?> service = this.service(request);
        ThriftServiceSchema schema = this.schema(service);
        ThriftMethodBinding method = schema.javaMethod(request.getTargetMethod());
        if (request.isMessage() != method.oneway) {
            throw application(TApplicationException.PROTOCOL_ERROR, "Meteor message mode differs from Thrift oneway");
        }

        String serviceAlias = alias(this.protocol, service, schema);
        if (!selected(this.protocol.option("service", null), serviceAlias, schema)) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Service is not selected by this Thrift endpoint");
        }

        String name = method.name;
        if (this.multiplexed) {
            name = serviceAlias + ":" + name;
        }

        Object arguments = method.arguments(request.getParameterValues());
        byte type = method.oneway ? TMessageType.ONEWAY : TMessageType.CALL;
        return this.wire.encodeBody(this.encoding, name, type, sequenceId, //
                output -> ThriftValueCodec.write(output, method.args, arguments));
    }

    public RequestPayload decodeRequest(ThriftMessage message) throws TException {
        return this.decodeRequest(message, null);
    }

    /** HTTP 路由传入已选择的挂载，字段映射不参与路径选择。 */
    public RequestPayload decodeRequest(ThriftMessage message, RouteMount mount) throws TException {
        byte type = message.messageType();
        if (type != TMessageType.CALL && type != TMessageType.ONEWAY) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "Expected a Thrift CALL or ONEWAY");
        }
        String wireName = message.methodName();
        int separator = wireName.indexOf(':');
        String selectedAlias = null;
        String methodName = wireName;
        if (this.multiplexed) {
            if (separator <= 0 || separator == wireName.length() - 1) {
                throw application(TApplicationException.UNKNOWN_METHOD, "Thrift multiplexing requires service:method");
            }
            selectedAlias = wireName.substring(0, separator);
            methodName = wireName.substring(separator + 1);
        } else if (separator >= 0) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Thrift multiplexing is disabled");
        }

        // 普通 Thrift 头不携带服务身份，当前挂载必须能唯一确定服务。
        String scopedService = this.protocol.option("service", null);
        MetBindInfo<?> selectedService = null;
        ThriftServiceSchema selectedSchema = null;
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> candidate = this.context.getServiceInfo(serviceId);
            boolean provider = candidate != null && candidate.getServiceType() == MetServiceType.Provider;
            if (!provider || candidate.isShadow() || !this.accepts(candidate, mount)) {
                continue;
            }
            ThriftServiceSchema schema = this.schema(candidate);
            String serviceAlias = alias(this.protocol, candidate, schema);
            boolean aliasMatches = selectedAlias == null || StringUtils.equals(selectedAlias, serviceAlias);
            if (!selected(scopedService, serviceAlias, schema) || !aliasMatches) {
                continue;
            }
            if (selectedService != null) {
                throw application(TApplicationException.UNKNOWN_METHOD, "Ambiguous Thrift service endpoint");
            }
            selectedService = candidate;
            selectedSchema = schema;
        }
        if (selectedService == null) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Thrift service is unavailable");
        }
        ThriftMethodBinding method = selectedSchema.wireMethod(methodName);
        if (method.oneway != (type == TMessageType.ONEWAY)) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "Message type differs from Thrift oneway");
        }
        Object arguments = this.readBody(message, method.args);

        // 线协议别名还原为 Java 方法名，之后仍由原 Dispatcher 调用普通接口。
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(selectedService.getBindGroup());
        request.setServiceName(selectedService.getBindName());
        request.setServiceVersion(selectedService.getBindVersion());
        request.setTargetMethod(method.method.getName());
        request.setSerializeType("thrift");
        request.setMessage(method.oneway);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        Class<?>[] types = method.method.getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            Object value = method.args.fields.get(index).get(arguments);
            value = ThriftMethodBinding.binary(value, types[index]);
            if (value == null && types[index].isPrimitive()) {
                value = BeanUtils.getDefaultValue(types[index]);
            }
            request.addParameter(types[index].getName(), value);
        }
        return request;
    }

    public String requestPath(RequestPayload request) {
        return this.routes.contextPath(this.service(request));
    }

    /** 用于会话关联响应；Java 方法名可能通过注解映射为不同的线协议方法名。 */
    public String requestMethod(RequestPayload request) throws TApplicationException {
        return this.schema(this.service(request)).javaMethod(request.getTargetMethod()).name;
    }

    public byte[] encodeResponse(RequestPayload request, ResponsePayload response, //
            ThriftMessage original) throws TException {
        ThriftMethodBinding method = this.schema(this.service(request)).javaMethod(request.getTargetMethod());
        if (method.oneway) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "A Thrift oneway has no response message");
        }
        Object result = method.result.newValue();
        if (response.getStatus() != ProtocolStatus.OK) {
            Throwable cause = unwrap(response.getFailure());
            for (ThriftFieldBinding field : method.result.fields) {
                if (field.field.id != 0 && field.type.javaType.isInstance(cause)) {
                    field.set(result, cause);
                    return this.encodeResult(original, method.result, result);
                }
            }
            return this.encodeError(original, cause);
        }
        if (method.method.getReturnType() != void.class) {
            if (response.getReturnData() == null) {
                int status = TApplicationException.MISSING_RESULT;
                TApplicationException missing = application(status, "Missing Thrift result");
                return this.encodeError(original, missing);
            }
            method.result.fieldsById.get((short) 0).set(result, response.getReturnData());
        }
        return this.encodeResult(original, method.result, result);
    }

    public ResponsePayload decodeResponse(RequestPayload request, ThriftMessage message) throws Throwable {
        ThriftMethodBinding method = this.schema(this.service(request)).javaMethod(request.getTargetMethod());
        if (!StringUtils.equals(method.name, message.methodName())) {
            throw application(TApplicationException.WRONG_METHOD_NAME, "Unexpected Thrift response method");
        }
        if (message.messageType() == TMessageType.EXCEPTION) {
            TMemoryInputTransport transport = this.inputTransport(message.data());
            TProtocol input = this.wire.createProtocol(message.encoding(), transport);
            input.readMessageBegin();
            TApplicationException exception = TApplicationException.readFrom(input);
            input.readMessageEnd();
            throw exception;
        }
        if (message.messageType() != TMessageType.REPLY || method.oneway) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "Expected a Thrift REPLY or EXCEPTION");
        }
        Object result = this.readBody(message, method.result);
        Object value = null;
        int fields = 0;
        for (ThriftFieldBinding field : method.result.fields) {
            Object member = field.get(result);
            if (member == null) {
                continue;
            }
            if (++fields > 1) {
                throw application(TApplicationException.PROTOCOL_ERROR, "Thrift reply contains multiple results");
            }
            if (field.field.id != 0) {
                throw (Throwable) member;
            }
            value = ThriftMethodBinding.binary(member, method.method.getReturnType());
        }
        Class<?> returnType = method.method.getReturnType();
        if (returnType != void.class && value == null) {
            throw application(TApplicationException.MISSING_RESULT, "Thrift success result is missing");
        }
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("thrift");
        response.setReceiveTime(System.currentTimeMillis());
        response.setReturnType(returnType.getName());
        response.setReturnData(value);
        return response;
    }

    public byte[] encodeError(ThriftMessage original, Throwable failure) throws TException {
        Throwable cause = unwrap(failure);
        TApplicationException error;
        if (cause instanceof TApplicationException application) {
            error = application;
        } else {
            String message = cause == null ? "Thrift service failed" : cause.getMessage();
            error = application(TApplicationException.INTERNAL_ERROR, message);
        }
        return this.wire.encodeException(original.encoding(), bareName(original), original.sequenceId(), error);
    }

    public static void validateOptions(ProtocolConfig protocol) {
        // 配置错误是部署失败，不因当前 Java 接口不支持 Thrift 而隐藏。
        validateEncoding(protocol.option("encoding", "binary"));
        booleanOption(protocol, "multiplexed", false);
        booleanOption(protocol, "framed", true);
        protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        protocol.integer("maxContainerSize", 100000);
        if (protocol.integer("maxDepth", 64) > 256) {
            throw new IllegalArgumentException("Thrift maxDepth must not exceed 256");
        }

    }

    private boolean accepts(MetBindInfo<?> service, RouteMount mount) {
        if (mount == null) {
            return this.routes.accepts(service);
        }
        return this.routes.accepts(service, mount);
    }

    private MetBindInfo<?> service(RequestPayload request) {
        MetBindInfo<?> service = this.context.getServiceInfo(request.getServiceGroup(), //
                request.getServiceName(), request.getServiceVersion());
        if (service == null || !this.routes.accepts(service)) {
            throw new IllegalArgumentException("Thrift service is unavailable");
        }
        return service;
    }

    private ThriftServiceSchema schema(MetBindInfo<?> service) {
        ProtocolBinding binding = this.routes.binding(service);
        if (binding != null && binding.getDefinition() instanceof ThriftServiceSchema schema) {
            return schema;
        }
        throw new IllegalArgumentException("Thrift service has no accepted contract");
    }

    public static String alias(ProtocolConfig protocol, MetBindInfo<?> service, ThriftServiceSchema schema) {
        String alias = service.getAliasName(protocol.name());
        return StringUtils.isBlank(alias) ? schema.getName() : alias;
    }

    public static boolean selected(String selection, String alias, ThriftServiceSchema schema) {
        boolean contractName = StringUtils.equals(selection, schema.getName());
        return selection == null || StringUtils.equals(selection, alias) || contractName;
    }

    private byte[] encodeResult(ThriftMessage original, ThriftType type, Object result) throws TException {
        return this.wire.encodeBody(original.encoding(), bareName(original), //
                TMessageType.REPLY, original.sequenceId(), output -> ThriftValueCodec.write(output, type, result));
    }

    private TMemoryInputTransport inputTransport(byte[] data) throws TException {
        TConfiguration limits = new TConfiguration(this.maxMessageSize, this.maxMessageSize, this.maxDepth + 1);
        return new TMemoryInputTransport(limits, data);
    }

    private Object readBody(ThriftMessage message, ThriftType type) throws TException {
        TMemoryInputTransport transport = this.inputTransport(message.data());
        TProtocol input = this.wire.createProtocol(message.encoding(), transport);
        input.readMessageBegin();
        Object value = ThriftValueCodec.read(input, type);
        input.readMessageEnd();
        if (transport.getBytesRemainingInBuffer() != 0) {
            throw application(TApplicationException.PROTOCOL_ERROR, "Trailing data after Thrift message");
        }
        return value;
    }

    private static void validateSerialization(String serialization) {
        if (serialization != null && !StringUtils.equalsIgnoreCase(serialization, "thrift")) {
            String encoding = "option(\"encoding\", \"binary|compact|json\")";
            String message = "Thrift requires its typed serialization; select wire encoding with " + encoding;
            throw new IllegalArgumentException(message);
        }
    }

    private static void validateEncoding(String encoding) {
        boolean supported = StringUtils.equals(encoding, "binary") || StringUtils.equals(encoding, "compact") || //
                StringUtils.equals(encoding, "json");
        if (!supported) {
            throw new IllegalArgumentException("Thrift encoding must be binary, compact or json");
        }
    }

    private static boolean booleanOption(ProtocolConfig protocol, String name, boolean fallback) {
        String value = protocol.option(name, Boolean.toString(fallback));
        if (!StringUtils.equals(value, "true") && !StringUtils.equals(value, "false")) {
            throw new IllegalArgumentException("Thrift option " + name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static String bareName(ThriftMessage original) {
        String name = original.methodName();
        int separator = name.indexOf(':');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static TApplicationException application(int type, String message) {
        return new TApplicationException(type, message);
    }
}
