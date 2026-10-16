/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;

/** XML-RPC 方法、Java 参数和结果映射；网络交换由会话持有。 */
public final class XmlRpcCodec {
    private static final ObjectMapper MAPPER = JsonMapper.builder() //
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) //
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT) //
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS) //
            .build();

    private final ProtocolConfig  protocol;
    private final MetContext      context;
    private final XmlRpcWireCodec wire;
    private final int             maxDepth;

    public XmlRpcCodec(ProtocolConfig protocol, MetContext context) {
        this.protocol = protocol;
        this.context = context;
        this.wire = new XmlRpcWireCodec(protocol);
        this.maxDepth = protocol.integer("maxDepth", 64);
    }

    public RequestPayload decodeRequest(byte[] body, RouteMount mount) throws IOException {
        XmlRpcCall call;
        try {
            call = this.wire.decodeRequest(body);
        } catch (IOException failure) {
            throw new XmlRpcException(-32700, "Parse error");
        }
        String scopedService = this.protocol.option("service", null);
        MetBindInfo<?> selectedService = null;
        Method selectedMethod = null;
        boolean nameFound = false;

        // 查找当前协议和挂载点允许发布的实例方法，不暴露订阅或隐藏服务。
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(serviceId);
            boolean provider = service != null && service.getServiceType() == MetServiceType.Provider;
            if (!provider || service.isShadow() || !this.protocol.accepts(service, mount)) {
                continue;
            }
            String alias = alias(service);
            if (scopedService != null && !StringUtils.equals(scopedService, alias)) {
                continue;
            }
            for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                String name = alias + "." + method.getName();
                if (scopedService != null) {
                    name = method.getName();
                }
                if (!StringUtils.equals(name, call.methodName())) {
                    continue;
                }
                nameFound = true;
                if (method.getParameterCount() != call.parameters().size()) {
                    continue;
                }
                if (selectedMethod != null) {
                    throw new XmlRpcException(-32602, "Ambiguous overloaded method");
                }
                selectedService = service;
                selectedMethod = method;
            }
        }
        if (selectedMethod == null) {
            if (nameFound) {
                throw new XmlRpcException(-32602, "Invalid params");
            }
            throw new XmlRpcException(-32601, "Method not found");
        }

        // XML-RPC 每次调用都有响应；入站 ID 留给会话分配。
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(selectedService.getBindGroup());
        request.setServiceName(selectedService.getBindName());
        request.setServiceVersion(selectedService.getBindVersion());
        request.setTargetMethod(selectedMethod.getName());
        request.setSerializeType("json");
        request.setMessage(false);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        Type[] types = selectedMethod.getGenericParameterTypes();
        try {
            for (int index = 0; index < types.length; index++) {
                JavaType target = this.memberType(selectedService, selectedMethod, types[index]);
                Object value = this.convert(call.parameters().get(index), target);
                request.addParameter(selectedMethod.getParameterTypes()[index].getName(), value);
            }
        } catch (IllegalArgumentException failure) {
            throw new XmlRpcException(-32602, "Invalid params");
        }
        return request;
    }

    /** 发布时拒绝 XML-RPC 无法表达的消息、流和未启用 nil 的空返回值。 */
    public static void validateService(ProtocolConfig protocol, MetBindInfo<?> service) {
        if (!protocol.accepts(service.getBindProtocols())) {
            return;
        }
        String alias = alias(service);
        if (StringUtils.isBlank(alias) || !alias.matches("[A-Za-z0-9_.:/]+")) {
            String message = "XML-RPC service alias must use letters, digits, underscore, dot, colon or slash";
            throw new IllegalArgumentException(message);
        }
        if (service.isMessage()) {
            throw new IllegalArgumentException("XML-RPC does not support message services");
        }
        boolean allowNil = Boolean.parseBoolean(protocol.option("allowNil", "false"));
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (!method.getName().matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid XML-RPC method name: " + method.getName());
            }
            if (method.isAnnotationPresent(MetMessage.class)) {
                throw new IllegalArgumentException("XML-RPC does not support message method: " + method.getName());
            }
            Class<?> result = method.getReturnType();
            boolean empty = result == void.class || result == Void.class;
            if (empty && !allowNil) {
                throw new IllegalArgumentException("XML-RPC void methods require allowNil: " + method.getName());
            }
            rejectStream(result);
            for (Class<?> parameter : method.getParameterTypes()) {
                rejectStream(parameter);
            }
        }
    }

    private static void rejectStream(Class<?> type) {
        boolean publisher = Flow.Publisher.class.isAssignableFrom(type);
        boolean input = InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        boolean output = OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type);
        if (publisher || input || output) {
            throw new IllegalArgumentException("XML-RPC does not support streaming type: " + type.getName());
        }
    }

    public byte[] encodeRequest(RequestPayload request) throws IOException {
        if (request.isMessage()) {
            throw new IOException("XML-RPC does not support notifications");
        }
        MetBindInfo<?> service = this.service(request);
        String alias = alias(service);
        String name = alias + "." + request.getTargetMethod();
        String scopedService = this.protocol.option("service", null);
        if (scopedService != null) {
            if (!StringUtils.equals(scopedService, alias)) {
                throw new IOException("Service is not selected by this XML-RPC endpoint");
            }
            name = request.getTargetMethod();
        }

        // 对象转为结构体时保留 Date 与 byte[]，由 XML 编码器选择各自的类型标签。
        List<Object> parameters = new ArrayList<>();
        try {
            for (Object value : request.getParameterValues()) {
                parameters.add(this.normalize(value, new IdentityHashMap<>(), 1));
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid XML-RPC request parameter", failure);
        }
        return this.wire.encodeRequest(name, parameters);
    }

    public String requestPath(RequestPayload request) {
        return this.protocol.contextPath(this.service(request));
    }

    public byte[] encodeResponse(ResponsePayload response) throws IOException {
        if (response.getStatus() != ProtocolStatus.OK) {
            return this.encodeError(response.getFailure());
        }
        try {
            Object value = this.normalize(response.getReturnData(), new IdentityHashMap<>(), 1);
            return this.wire.encodeResponse(value);
        } catch (IllegalArgumentException | IOException failure) {
            return this.wire.encodeFault(-32603, "Internal error");
        }
    }

    public byte[] encodeError(Throwable failure) throws IOException {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException) {
            cause = cause.getCause();
        }
        if (cause instanceof XmlRpcException error) {
            return this.wire.encodeFault(error.getCode(), error.getMessage());
        }
        return this.wire.encodeFault(-32603, "Internal error");
    }

    public ResponsePayload decodeResponse(RequestPayload request, byte[] body) throws IOException {
        Object decoded = this.wire.decodeResponse(body);
        Method method = this.method(request);
        Object value = null;
        if (method.getReturnType() != void.class) {
            try {
                JavaType target = this.memberType(this.service(request), method, method.getGenericReturnType());
                value = this.convert(decoded, target);
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid XML-RPC result type", failure);
            }
        } else if (decoded != null) {
            throw new IOException("XML-RPC void result must be nil");
        }
        ResponsePayload result = new ResponsePayload();
        result.setRequestID(request.getRequestID());
        result.setStatus(ProtocolStatus.OK);
        result.setSerializeType("json");
        result.setReceiveTime(System.currentTimeMillis());
        result.setReturnType(method.getReturnType().getName());
        result.setReturnData(value);
        return result;
    }

    private JavaType memberType(MetBindInfo<?> service, Method method, Type type) {
        JavaType declared = MAPPER.getTypeFactory().constructType(service.getBindType());
        JavaType owner = declared.findSuperType(method.getDeclaringClass());
        return MAPPER.getTypeFactory().resolveMemberType(type, owner.getBindings());
    }

    private Object convert(Object value, JavaType target) {
        Class<?> type = target.getRawClass();
        if (value == null) {
            require(!type.isPrimitive());
            return null;
        }
        if (type == Object.class) {
            return value;
        }
        if (Date.class.isAssignableFrom(type)) {
            require(value instanceof Date);
        } else if (type == byte[].class) {
            require(value instanceof byte[]);
        } else if (type == String.class || type.isEnum() || type == char.class || type == Character.class) {
            require(value instanceof String);
        } else if (type == boolean.class || type == Boolean.class) {
            require(value instanceof Boolean);
        } else if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            require(value instanceof Number);
            boolean decimal = type == float.class || type == Float.class || type == BigDecimal.class;
            decimal = decimal || type == double.class || type == Double.class || type == Number.class;
            if (!decimal) {
                require(!(value instanceof Float) && !(value instanceof Double));
            }
            if (type == float.class || type == Float.class) {
                require(Float.isFinite(((Number) value).floatValue()));
            }
            if (type == byte.class || type == Byte.class) {
                long number = ((Number) value).longValue();
                require(number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE);
            }
            if (type == short.class || type == Short.class) {
                long number = ((Number) value).longValue();
                require(number >= Short.MIN_VALUE && number <= Short.MAX_VALUE);
            }
        } else if (target.isArrayType()) {
            require(value instanceof List<?>);
            List<?> values = (List<?>) value;
            JavaType component = target.getContentType();
            Object array = Array.newInstance(component.getRawClass(), values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(array, index, this.convert(values.get(index), component));
            }
            return array;
        } else if (target.isCollectionLikeType()) {
            require(value instanceof List<?>);
            Collection<Object> elements = MAPPER.convertValue(List.of(), target);
            for (Object element : (List<?>) value) {
                elements.add(this.convert(element, target.getContentType()));
            }
            return elements;
        } else if (target.isMapLikeType()) {
            require(value instanceof Map<?, ?>);
            Class<?> key = target.getKeyType().getRawClass();
            require(key == Object.class || key == String.class);
            Map<String, Object> members = MAPPER.convertValue(Map.of(), target);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                members.put((String) entry.getKey(), this.convert(entry.getValue(), target.getContentType()));
            }
            return members;
        } else {
            require(value instanceof Map<?, ?>);
            return this.convertBean((Map<?, ?>) value, target);
        }
        if (type.isInstance(value)) {
            return value;
        }
        return MAPPER.convertValue(value, target);
    }

    private Object convertBean(Map<?, ?> values, JavaType target) {
        BeanDescription bean = MAPPER.getDeserializationConfig().introspect(target);
        Object result = bean.instantiateBean(true);
        if (result == null) {
            throw new IllegalArgumentException("XML-RPC DTO requires a no-argument constructor");
        }
        Map<String, BeanPropertyDefinition> properties = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : bean.findProperties()) {
            properties.put(property.getName(), property);
        }

        // 按成员的泛型类型逐项写入，Object 类型字段保留原始日期和二进制对象。
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            BeanPropertyDefinition property = properties.get(entry.getKey());
            require(property != null);
            AnnotatedMember mutator = property.getNonConstructorMutator();
            require(mutator != null);
            Object member = this.convert(entry.getValue(), property.getPrimaryType());
            mutator.fixAccess(true);
            mutator.setValue(result, member);
        }
        return result;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("XML-RPC value does not match Java declaration");
        }
    }

    private Object normalize(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (depth > this.maxDepth) {
            throw new IllegalArgumentException("XML-RPC value exceeds maxDepth");
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Date || value instanceof byte[]) {
            return value;
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Character) {
            return value.toString();
        }
        rejectStream(value.getClass());
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("XML-RPC values cannot contain object cycles");
        }

        // 只保留当前递归路径，重复引用可以正常展开，真正的循环引用则明确报错。
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> members = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    require(entry.getKey() instanceof String);
                    members.put((String) entry.getKey(), this.normalize(entry.getValue(), visiting, depth + 1));
                }
                return members;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> elements = new ArrayList<>();
                for (Object element : collection) {
                    elements.add(this.normalize(element, visiting, depth + 1));
                }
                return elements;
            }
            if (value.getClass().isArray()) {
                List<Object> elements = new ArrayList<>();
                for (int index = 0; index < Array.getLength(value); index++) {
                    elements.add(this.normalize(Array.get(value, index), visiting, depth + 1));
                }
                return elements;
            }

            // DTO 属性按公开的 Jackson 属性声明展开，不经 JSON 字符串中转。
            JavaType type = MAPPER.getTypeFactory().constructType(value.getClass());
            BeanDescription bean = MAPPER.getSerializationConfig().introspect(type);
            Map<String, Object> members = new LinkedHashMap<>();
            for (BeanPropertyDefinition property : bean.findProperties()) {
                AnnotatedMember accessor = property.getAccessor();
                if (accessor != null && property.couldSerialize()) {
                    accessor.fixAccess(true);
                    Object member = accessor.getValue(value);
                    members.put(property.getName(), this.normalize(member, visiting, depth + 1));
                }
            }
            if (members.isEmpty()) {
                throw new IllegalArgumentException("XML-RPC struct requires readable properties");
            }
            return members;
        } finally {
            visiting.remove(value);
        }
    }

    private Method method(RequestPayload request) throws IOException {
        Class<?>[] types = new Class<?>[request.getParameterTypes().size()];
        try {
            for (int index = 0; index < types.length; index++) {
                String name = request.getParameterTypes().get(index);
                types[index] = MetRuntimeUtils.toJavaType(name, this.context.getClassLoader());
            }
            Class<?> serviceType = this.service(request).getBindType();
            return MetRuntimeUtils.getServiceMethod(serviceType, request.getTargetMethod(), types);
        } catch (Exception failure) {
            throw new IOException("XML-RPC consumer method is unavailable", failure);
        }
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        return this.context.getServiceInfo(group, name, request.getServiceVersion());
    }

    private static String alias(MetBindInfo<?> service) {
        String alias = service.getAliasName("xmlrpc");
        return StringUtils.isBlank(alias) ? service.getBindName() : alias;
    }
}
