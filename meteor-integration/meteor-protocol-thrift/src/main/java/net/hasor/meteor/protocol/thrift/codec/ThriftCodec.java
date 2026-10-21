/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.*;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryInputTransport;

/** Maps native generated Thrift args/results to Meteor payloads without invoking a generated Processor. */
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
        validateEncoding(this.encoding);
        this.multiplexed = booleanOption(protocol, "multiplexed", false);
        this.maxMessageSize = protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        this.maxDepth = protocol.integer("maxDepth", 64);
        int maxContainerSize = protocol.integer("maxContainerSize", 100000);
        this.wire = new ThriftWireCodec(this.maxMessageSize, maxContainerSize, this.maxDepth);
    }

    public byte[] encodeRequest(RequestPayload request, int sequenceId) throws TException {
        validateSerialization(request.getSerializeType());
        MetBindInfo<?> service = this.service(request);
        ThriftServiceResource resource = this.resource(service);
        Method method = method(service, request.getTargetMethod());
        ProcessFunction<?, ?, ?> function = function(resource, method.getName());
        boolean oneway = function.isOneway();
        if (request.isMessage() != oneway) {
            throw application(TApplicationException.PROTOCOL_ERROR, "Meteor message mode differs from IDL oneway");
        }
        String serviceAlias = alias(this.protocol, service, resource);
        if (!selected(this.protocol.option("service", null), serviceAlias, resource)) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Service is not selected by this Thrift endpoint");
        }
        String name = method.getName();
        if (this.multiplexed) {
            name = serviceAlias + ":" + name;
        }
        TBase<?, ?> args = function.getEmptyArgsInstance();
        List<TFieldIdEnum> fields = fields(args);
        List<Object> values = request.getParameterValues();
        if (values.size() != fields.size()) {
            throw application(TApplicationException.PROTOCOL_ERROR, "Thrift argument count differs from IDL");
        }
        Class<?>[] generatedTypes = resource.getMethods().get(method.getName()).getParameterTypes();
        try {
            for (int index = 0; index < fields.size(); index++) {
                Object value = convertBinary(values.get(index), generatedTypes[index]);
                set(args, fields.get(index), value);
            }
        } catch (RuntimeException failure) {
            throw new TException("Invalid Thrift argument value", failure);
        }
        byte type = oneway ? TMessageType.ONEWAY : TMessageType.CALL;
        return this.wire.encode(this.encoding, name, type, sequenceId, args);
    }

    public RequestPayload decodeRequest(ThriftMessage message) throws TException {
        return this.decodeRequest(message, null);
    }

    /** The owning HTTP route supplies its already selected mount, including exact service paths. */
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

        // A non-multiplexed header cannot identify a service: its mounted namespace must be unambiguous.
        String scopedService = this.protocol.option("service", null);
        MetBindInfo<?> selectedService = null;
        ThriftServiceResource selectedResource = null;
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> candidate = this.context.getServiceInfo(serviceId);
            boolean provider = candidate != null && candidate.getServiceType() == MetServiceType.Provider;
            if (!provider || candidate.isShadow() || !this.accepts(candidate, mount)) {
                continue;
            }
            ThriftServiceResource resource = this.resource(candidate);
            String serviceAlias = alias(this.protocol, candidate, resource);
            boolean aliasMatches = selectedAlias == null || StringUtils.equals(selectedAlias, serviceAlias);
            if (!selected(scopedService, serviceAlias, resource) || !aliasMatches) {
                continue;
            }
            if (selectedService != null) {
                throw application(TApplicationException.UNKNOWN_METHOD, "Ambiguous Thrift service endpoint");
            }
            selectedService = candidate;
            selectedResource = resource;
        }
        if (selectedService == null) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Thrift service is unavailable");
        }
        Method method = method(selectedService, methodName);
        ProcessFunction<?, ?, ?> function = function(selectedResource, methodName);
        if (function.isOneway() != (type == TMessageType.ONEWAY)) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "Message type differs from IDL oneway");
        }
        TBase<?, ?> args = function.getEmptyArgsInstance();
        this.readBody(message, args);

        RequestPayload request = new RequestPayload();
        request.setServiceGroup(selectedService.getBindGroup());
        request.setServiceName(selectedService.getBindName());
        request.setServiceVersion(selectedService.getBindVersion());
        request.setTargetMethod(methodName);
        request.setSerializeType("thrift");
        request.setMessage(function.isOneway());
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        Class<?>[] types = method.getParameterTypes();
        List<TFieldIdEnum> fields = fields(args);
        for (int index = 0; index < fields.size(); index++) {
            Object value = convertBinary(get(args, fields.get(index)), types[index]);
            request.addParameter(types[index].getName(), value);
        }
        return request;
    }

    public String requestPath(RequestPayload request) {
        return this.routes.contextPath(this.service(request));
    }

    public byte[] encodeResponse(RequestPayload request, ResponsePayload response, //
            ThriftMessage original) throws TException {
        ThriftServiceResource resource = this.resource(this.service(request));
        ProcessFunction<?, ?, ?> function = function(resource, request.getTargetMethod());
        if (function.isOneway()) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "An IDL oneway has no response message");
        }
        TBase<?, ?> result = function.getEmptyResultInstance();
        if (response.getStatus() != ProtocolStatus.OK) {
            Throwable cause = unwrap(response.getFailure());
            if (cause != null) {
                for (TFieldIdEnum field : fields(result)) {
                    if (field.getThriftFieldId() == 0) {
                        continue;
                    }
                    FieldMetaData metadata = metadata(result).get(field);
                    if (metadata.valueMetaData instanceof StructMetaData struct) {
                        if (struct.structClass.isInstance(cause)) {
                            set(result, field, cause);
                            return this.encodeResult(original, result);
                        }
                    }
                }
            }
            return this.encodeError(original, cause);
        }
        Method generated = resource.getMethods().get(request.getTargetMethod());
        if (generated.getReturnType() != void.class) {
            TFieldIdEnum success = result.fieldForId(0);
            if (success == null || response.getReturnData() == null) {
                String message = "Missing Thrift result";
                TApplicationException missing = application(TApplicationException.MISSING_RESULT, message);
                return this.encodeError(original, missing);
            }
            set(result, success, convertBinary(response.getReturnData(), generated.getReturnType()));
        }
        return this.encodeResult(original, result);
    }

    public ResponsePayload decodeResponse(RequestPayload request, ThriftMessage message) throws Throwable {
        if (!StringUtils.equals(request.getTargetMethod(), message.methodName())) {
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
        if (message.messageType() != TMessageType.REPLY) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "Expected a Thrift REPLY or EXCEPTION");
        }
        MetBindInfo<?> service = this.service(request);
        Method method = method(service, request.getTargetMethod());
        ProcessFunction<?, ?, ?> function = function(this.resource(service), request.getTargetMethod());
        TBase<?, ?> result = function.getEmptyResultInstance();
        if (result == null) {
            throw application(TApplicationException.INVALID_MESSAGE_TYPE, "An IDL oneway has no response message");
        }
        this.readBody(message, result);
        TFieldIdEnum success = result.fieldForId(0);
        Object value = null;
        if (success != null && isSet(result, success)) {
            value = convertBinary(get(result, success), method.getReturnType());
        } else {
            for (TFieldIdEnum field : fields(result)) {
                if (field.getThriftFieldId() != 0 && isSet(result, field)) {
                    Object failure = get(result, field);
                    if (failure instanceof Throwable declared) {
                        throw declared;
                    }
                    throw application(TApplicationException.PROTOCOL_ERROR, "Invalid declared Thrift exception");
                }
            }
            if (method.getReturnType() != void.class) {
                throw application(TApplicationException.MISSING_RESULT, "Thrift success result is missing");
            }
        }
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("thrift");
        response.setReceiveTime(System.currentTimeMillis());
        response.setReturnType(method.getReturnType().getName());
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

    /** 准备静态 IDL 契约；不支持的 Java 门面只退出当前协议，不改变服务本身。 */
    public static ThriftServiceResource validateService(ProtocolConfig protocol, MetBindInfo<?> service) {
        // 配置错误是部署失败，不因当前 Java 接口不支持 Thrift 而隐藏。
        validateEncoding(protocol.option("encoding", "binary"));
        booleanOption(protocol, "multiplexed", false);
        booleanOption(protocol, "framed", true);
        protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        protocol.integer("maxContainerSize", 100000);
        if (protocol.integer("maxDepth", 64) > 256) {
            throw new IllegalArgumentException("Thrift maxDepth must not exceed 256");
        }

        Object bound = service.getBindResource();
        if (bound != null && !(bound instanceof ThriftServiceResource)) {
            return null;
        }
        String serializer = service.getSerializeType();
        if (serializer != null && !StringUtils.equalsIgnoreCase(serializer, "thrift")) {
            return null;
        }

        // 当前接入点准备自己的只读 IDL 定义，不依赖其他端点的接受结果。
        ThriftServiceResource resource = boundResource(service);
        if (resource == null) {
            return null;
        }
        String alias = alias(protocol, service, resource);
        if (StringUtils.isBlank(alias) || alias.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Thrift service alias must be nonempty and cannot contain ':'");
        }
        if (!selected(protocol.option("service", null), alias, resource)) {
            return null;
        }

        // Java 方法签名必须能用已有 IDL 表达，不能为不支持的接口构造线协议约定。
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method candidate : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (candidate.isBridge() || candidate.isSynthetic()) {
                continue;
            }
            Method method = MetRuntimeUtils.getServiceMethod(service.getBindType(), //
                    candidate.getName(), candidate.getParameterTypes());
            Method existing = methods.putIfAbsent(method.getName(), method);
            if (existing != null) {
                if (!Arrays.equals(existing.getParameterTypes(), method.getParameterTypes())) {
                    return null;
                }
                continue;
            }
            Method generated = resource.getMethods().get(method.getName());
            ProcessFunction<?, ?, ?> function = resource.getFunctions().get(method.getName());
            if (generated == null || function == null) {
                return null;
            }
            Type[] declared = method.getGenericParameterTypes();
            Type[] nativeTypes = generated.getGenericParameterTypes();
            if (declared.length != nativeTypes.length) {
                return null;
            }
            boolean result = !streaming(method.getReturnType()) && //
                    compatible(method.getGenericReturnType(), generated.getGenericReturnType());
            if (!result) {
                return null;
            }
            for (int index = 0; index < declared.length; index++) {
                boolean parameter = !streaming(method.getParameterTypes()[index]) && //
                        compatible(declared[index], nativeTypes[index]);
                if (!parameter) {
                    return null;
                }
            }
            boolean message = service.isMessage() || method.isAnnotationPresent(MetMessage.class);
            if (message != function.isOneway()) {
                return null;
            }
            if (fields(function.getEmptyArgsInstance()).size() != declared.length) {
                throw new IllegalArgumentException("Thrift args metadata differs from Iface: " + method.getName());
            }
        }
        return resource;
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

    private ThriftServiceResource resource(MetBindInfo<?> service) {
        ProtocolBinding binding = this.routes.binding(service);
        if (binding != null && binding.getDefinition() instanceof ThriftServiceResource resource) {
            return resource;
        }
        throw new IllegalArgumentException("Thrift service has no accepted IDL contract");
    }

    private static ThriftServiceResource boundResource(MetBindInfo<?> service) {
        Object resource = service.getBindResource();
        if (resource instanceof ThriftServiceResource thrift) {
            return thrift;
        }
        if (resource != null) {
            throw new IllegalArgumentException("Thrift requires ThriftServiceResource as its local bound resource");
        }
        Class<?> generatedIface = inferredGeneratedIface(service.getBindType());
        if (generatedIface == null) {
            return null;
        }
        return new ThriftServiceResource(generatedIface.getDeclaringClass());
    }

    private static Class<?> inferredGeneratedIface(Class<?> type) {
        boolean generatedIface = type.isInterface() && StringUtils.equals(type.getSimpleName(), "Iface");
        if (generatedIface && type.getDeclaringClass() != null) {
            return type;
        }
        Class<?> selected = null;
        for (Class<?> parent : type.getInterfaces()) {
            Class<?> candidate = inferredGeneratedIface(parent);
            if (candidate == null) {
                continue;
            }
            if (selected == null || selected.isAssignableFrom(candidate)) {
                selected = candidate;
            } else if (!candidate.isAssignableFrom(selected)) {
                return null;
            }
        }
        return selected;
    }

    private static Method method(MetBindInfo<?> service, String name) throws TApplicationException {
        Method selected = null;
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (!method.isBridge() && !method.isSynthetic() && StringUtils.equals(method.getName(), name)) {
                if (selected != null && !Arrays.equals(selected.getParameterTypes(), method.getParameterTypes())) {
                    throw application(TApplicationException.UNKNOWN_METHOD, "Ambiguous overloaded Thrift method");
                }
                selected = method;
            }
        }
        if (selected == null) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Unknown Thrift method: " + name);
        }
        return MetRuntimeUtils.getServiceMethod(service.getBindType(), name, selected.getParameterTypes());
    }

    private static ProcessFunction<?, ?, ?> function(ThriftServiceResource resource, //
            String name) throws TApplicationException {
        ProcessFunction<?, ?, ?> function = resource.getFunctions().get(name);
        if (function == null) {
            throw application(TApplicationException.UNKNOWN_METHOD, "Unknown Thrift method: " + name);
        }
        return function;
    }

    private static String alias(ProtocolConfig protocol, MetBindInfo<?> service, ThriftServiceResource resource) {
        String alias = service.getAliasName(protocol.name());
        return StringUtils.isBlank(alias) ? resource.getServiceName() : alias;
    }

    private static boolean selected(String selection, String alias, ThriftServiceResource resource) {
        boolean generatedName = StringUtils.equals(selection, resource.getServiceName());
        return selection == null || StringUtils.equals(selection, alias) || generatedName;
    }

    private byte[] encodeResult(ThriftMessage original, TBase<?, ?> result) throws TException {
        return this.wire.encode(original.encoding(), bareName(original), //
                TMessageType.REPLY, original.sequenceId(), result);
    }

    private TMemoryInputTransport inputTransport(byte[] data) throws TException {
        TConfiguration limits = new TConfiguration(this.maxMessageSize, this.maxMessageSize, this.maxDepth + 1);
        return new TMemoryInputTransport(limits, data);
    }

    private void readBody(ThriftMessage message, TBase<?, ?> body) throws TException {
        TMemoryInputTransport transport = this.inputTransport(message.data());
        TProtocol input = this.wire.createProtocol(message.encoding(), transport);
        input.readMessageBegin();
        body.read(input);
        input.readMessageEnd();
        if (transport.getBytesRemainingInBuffer() != 0) {
            throw application(TApplicationException.PROTOCOL_ERROR, "Trailing data after Thrift message");
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Map<TFieldIdEnum, FieldMetaData> metadata(TBase<?, ?> value) {
        return (Map) FieldMetaData.getStructMetaDataMap((Class) value.getClass());
    }

    private static List<TFieldIdEnum> fields(TBase<?, ?> value) {
        List<TFieldIdEnum> fields = new ArrayList<>(metadata(value).keySet());
        // Java generator emits enum constants in IDL declaration order, independent of field IDs.
        fields.sort(Comparator.comparingInt(field -> ((Enum<?>) field).ordinal()));
        return fields;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void set(TBase value, TFieldIdEnum field, Object member) {
        value.setFieldValue(field, member);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object get(TBase value, TFieldIdEnum field) {
        return value.getFieldValue(field);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static boolean isSet(TBase value, TFieldIdEnum field) {
        return value.isSet(field);
    }

    private static Object convertBinary(Object value, Class<?> target) {
        if (value instanceof byte[] bytes && target == ByteBuffer.class) {
            return ByteBuffer.wrap(bytes);
        }
        if (value instanceof ByteBuffer buffer && target == byte[].class) {
            ByteBuffer source = buffer.duplicate();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            return bytes;
        }
        return value;
    }

    private static boolean compatible(Type local, Type generated) {
        if (local.equals(generated)) {
            return true;
        }
        boolean localBytes = local == byte[].class && generated == ByteBuffer.class;
        boolean generatedBytes = local == ByteBuffer.class && generated == byte[].class;
        if (localBytes || generatedBytes) {
            return true;
        }
        if (local instanceof ParameterizedType left && generated instanceof ParameterizedType right) {
            // Generic container values retain the SDK's declared representation.
            return left.getRawType().equals(right.getRawType()) && //
                    Arrays.equals(left.getActualTypeArguments(), right.getActualTypeArguments());
        }
        return false;
    }

    private static boolean streaming(Class<?> type) {
        boolean input = InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        boolean output = OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type);
        return input || output || Flow.Publisher.class.isAssignableFrom(type);
    }

    private static void validateSerialization(String serialization) {
        if (serialization != null && !StringUtils.equalsIgnoreCase(serialization, "thrift")) {
            String encoding = "option(\"encoding\", \"binary|compact|json\")";
            String message = "Thrift requires generated IDL serialization; select wire encoding with " + encoding;
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
