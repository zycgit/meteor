/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.PropertyMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** Dubbo 经典二进制协议到 Meteor 请求／响应的映射，仅使用 Hessian 2 序列化。 */
public final class DubboCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final  int                           DEFAULT_MAX_FRAME_SIZE = 16 * 1024 * 1024;
    private static final String                        VERSION                = "2.0.2";
    private final        ProtocolConfig                config;
    private final        RouteMountIndex               routes;
    private final        MetContext                    context;
    private final        int                           maximum;
    private final        DubboHessianSerializerFactory serializers;

    public DubboCodec(ProtocolConfig config, RouteMountIndex routes, MetContext context) {
        this.config = config;
        this.routes = routes;
        this.context = context;
        this.maximum = config.integer("maxFrameSize", DEFAULT_MAX_FRAME_SIZE);
        this.serializers = new DubboHessianSerializerFactory(context.getClassLoader());
    }

    /** 发布时确认 Java 签名属于经典 Dubbo 普通调用，不接受流或远程对象引用。 */
    public static Map<Method, String> prepareService(MetBindInfo<?> service) {
        String serializer = service.getSerializeType();
        if (serializer != null && !StringUtils.equalsIgnoreCase(serializer, "Hessian2")) {
            return null;
        }
        Map<Method, String> methods = new LinkedHashMap<>();
        Map<String, Method> signatures = new LinkedHashMap<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            String name = MetRuntimeUtils.getProtocolMethodName(method);
            String signature = name + descriptor(method);
            if (signatures.putIfAbsent(signature, method) != null) {
                return null;
            }
            if (!supportsType(method.getReturnType())) {
                return null;
            }
            for (Class<?> type : method.getParameterTypes()) {
                if (!supportsType(type)) {
                    return null;
                }
            }
            methods.put(method, name);
        }
        return java.util.Collections.unmodifiableMap(methods);
    }

    public byte[] encode(RequestPayload request) throws IOException {
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request);
        List<Object> values = request.getParameterValues();
        if (values.size() != method.getParameterCount()) {
            throw new IOException("Dubbo argument count differs from published method");
        }

        // 方法描述只由本地发布接口产生，不从远端提供的类型名称加载类。
        DubboBoundedOutputStream bytes = new DubboBoundedOutputStream(this.maximum);
        Hessian2Output output = this.output(bytes);
        output.writeString(VERSION);
        output.writeString(this.alias(service));
        String requestedVersion = request.getOption("dubbo.version");
        String selectedVersion = this.wireVersion(service);
        if (requestedVersion != null) {
            selectedVersion = normalizeVersion(requestedVersion);
        }
        output.writeString(selectedVersion);
        output.writeString(this.methods(service).get(method));
        output.writeString(descriptor(method));
        for (Object value : values) {
            output.writeObject(value);
        }

        // 协议身份、期限在用户附加项之后写入，用户选项不覆盖调用目标。
        Map<String, Object> attachments = attachments(request);
        attachments.put("path", this.alias(service));
        attachments.put("interface", this.alias(service));
        attachments.put("dubbo", VERSION);
        String requestedGroup = request.getOption("dubbo.group");
        String group = requestedGroup == null ? this.wireGroup(service) : requestedGroup;
        if (!group.isEmpty()) {
            attachments.put("group", group);
        } else {
            attachments.remove("group");
        }
        if (!selectedVersion.isEmpty()) {
            attachments.put("version", selectedVersion);
        } else {
            attachments.remove("version");
        }
        if (request.getClientTimeout() > 0) {
            attachments.put("timeout", Integer.toString(request.getClientTimeout()));
        }
        attachments.remove("dubbo.group");
        attachments.remove("dubbo.version");
        output.writeObject(attachments);
        output.flush();
        byte flags = (byte) (request.isMessage() ? 0x82 : 0xc2);
        return this.frame(flags, (byte) 0, request.getRequestID(), bytes.toByteArray());
    }

    public RequestPayload decodeRequest(DubboFrame frame) throws IOException {
        this.require(frame, true);
        Hessian2Input input = this.input(frame.body());
        String dubboVersion = input.readString();
        String path = input.readString();
        String version = input.readString();
        String methodName = input.readString();
        String signature = input.readString();
        if (StringUtils.isBlank(dubboVersion) || StringUtils.isBlank(path) || StringUtils.isBlank(methodName)) {
            throw new IOException("Incomplete Dubbo request identity");
        }

        // 泛化调用只携带 JDK 值和 Map，参数类型仅用于匹配已发布方法，不参与类加载。
        boolean generic = StringUtils.equals(methodName, "$invoke");
        String[] genericTypes = null;
        Object[] values;
        Class<?>[] types;
        if (generic) {
            String expected = "Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;";
            if (!StringUtils.equals(expected, signature)) {
                throw new IOException("Invalid Dubbo generic invocation descriptor");
            }
            methodName = (String) this.readValue(input, String.class);
            genericTypes = (String[]) this.readValue(input, String[].class);
            values = (Object[]) this.readValue(input, Object[].class);
            if (genericTypes == null || values == null || genericTypes.length != values.length) {
                throw new IOException("Dubbo generic argument types and values disagree");
            }
            types = null;
        } else {
            // 普通调用先锁定参数声明，再按本地类型解码，不从报文加载类型。
            Method method = this.requestMethod(path, version, methodName, signature);
            types = method.getParameterTypes();
            values = new Object[method.getParameterCount()];
            for (int index = 0; index < values.length; index++) {
                values[index] = this.readValue(input, types[index]);
            }
        }
        Map<?, ?> attachments = this.readAttachments(input);
        this.end(input);

        // group 位于正文末尾；解析后完成提供者选择，防止跨分组派发。
        String group = text(attachments.get("group"));
        String attachmentVersion = text(attachments.get("version"));
        if (!StringUtils.isEmpty(attachmentVersion) && !StringUtils.equals(version, attachmentVersion)) {
            throw new IOException("Dubbo service version differs from attachment version");
        }
        String selectedVersion = StringUtils.isEmpty(attachmentVersion) ? version : attachmentVersion;
        MetBindInfo<?> service = this.findService(path, group, selectedVersion);
        Method selected;
        if (generic) {
            selected = genericMethod(service, methodName, genericTypes);
            types = selected.getParameterTypes();
            MethodMeta description = ServiceMetaParser.method(service.getBindType(), selected);
            JavaType[] declared = ServiceMetaParser.parameterTypes(description, JSON.getTypeFactory());
            for (int index = 0; index < values.length; index++) {
                values[index] = this.realizeGeneric(values[index], declared[index], 0);
            }
        } else {
            selected = findMethod(service, methodName, signature);
            if (!List.of(selected.getParameterTypes()).equals(List.of(types))) {
                throw new IOException("Dubbo service groups have inconsistent method signatures");
            }
        }

        RequestPayload request = new RequestPayload();
        request.setRequestID(frame.requestId());
        request.setMessage(!frame.twoWay());
        request.setReceiveTime(System.currentTimeMillis());
        request.setServiceName(service.getBindName());
        request.setServiceGroup(service.getBindGroup());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(selected.getName());
        request.setSerializeType("Hessian2");
        copyAttachments(attachments, request);
        request.removeOption("dubbo.binary.generic");
        if (generic) {
            request.addOption("dubbo.binary.generic", "true");
        }
        String timeout = text(attachments.get("timeout"));
        if (timeout != null) {
            try {
                int millis = Integer.parseInt(timeout);
                if (millis <= 0) {
                    throw new NumberFormatException("not positive");
                }
                request.setClientTimeout(millis);
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid Dubbo timeout", failure);
            }
        }
        for (int index = 0; index < values.length; index++) {
            request.addParameter(types[index].getName(), values[index]);
        }
        return request;
    }

    public byte[] encode(RequestPayload incoming, ResponsePayload response) throws IOException {
        // 错误状态没有业务结果；服务抛出的异常沿正常 Dubbo 响应异常分支返回。
        Throwable failure = response.getFailure();
        if (failure == null && response.getStatus() != ProtocolStatus.OK) {
            return this.error(incoming.getRequestID(), status(response.getStatus()), response.getOption("message"));
        }
        DubboBoundedOutputStream bytes = new DubboBoundedOutputStream(this.maximum);
        Hessian2Output output = this.output(bytes);
        Object value = response.getReturnData();
        if (value != null && StringUtils.equals(incoming.getOption("dubbo.binary.generic"), "true")) {
            value = generalize(value);
        }
        if (failure != null) {
            output.writeInt(3);
            output.writeObject(failure);
        } else if (value == null) {
            output.writeInt(5);
        } else {
            output.writeInt(4);
            output.writeObject(value);
        }
        Map<String, Object> attachments = attachments(response);
        attachments.put("dubbo", VERSION);
        output.writeObject(attachments);
        output.flush();
        return this.frame((byte) 2, (byte) 20, incoming.getRequestID(), bytes.toByteArray());
    }

    public ResponsePayload decodeResponse(RequestPayload outgoing, DubboFrame frame) throws IOException {
        this.require(frame, false);
        if (frame.requestId() != outgoing.getRequestID()) {
            throw new IOException("Dubbo response request ID differs from pending request");
        }
        Hessian2Input input = this.input(frame.body());
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(frame.requestId());
        response.setReceiveTime(System.currentTimeMillis());
        response.setSerializeType("Hessian2");
        if (frame.status() != 20) {
            String message = input.readString();
            this.end(input);
            response.setStatus(protocolStatus(frame.status()));
            response.addOption("message", message);
            response.setFailure(new IOException(message));
            return response;
        }

        // 0..2 是旧式无附加项结果，3..5 为携带附加项的对应结果。
        int flag = input.readInt();
        if (flag < 0 || flag > 5) {
            throw new IOException("Unknown Dubbo response value flag: " + flag);
        }
        Method method = this.method(this.service(outgoing), outgoing);
        boolean exceptional = flag == 0 || flag == 3;
        boolean hasValue = flag == 1 || flag == 4;
        if (exceptional) {
            Object value = input.readObject();
            if (!(value instanceof Throwable failure)) {
                throw new IOException("Dubbo exception response does not contain a Throwable");
            }
            response.setFailure(failure);
            response.setStatus(ProtocolStatus.InvokeError);
            response.addOption("message", failure.getMessage());
        } else {
            Object value = hasValue ? this.readValue(input, method.getReturnType()) : null;
            if (value == null && method.getReturnType().isPrimitive() && method.getReturnType() != void.class) {
                throw new IOException("Null Dubbo result cannot match a primitive return type");
            }
            response.setStatus(ProtocolStatus.OK);
            response.setReturnType(method.getReturnType().getName());
            response.setReturnData(value);
        }
        if (flag >= 3) {
            copyAttachments(this.readAttachments(input), response);
        }
        this.end(input);
        return response;
    }

    /** 空 event 正文是心跳；只读与其它事件由会话按 eventValue 区分。 */
    public Object eventValue(DubboFrame frame) throws IOException {
        this.serialization(frame);
        if (!frame.event()) {
            throw new IOException("Expected Dubbo event frame");
        }
        Hessian2Input input = this.input(frame.body());
        Object value = input.readObject();
        this.end(input);
        return value;
    }

    public byte[] heartbeat(DubboFrame incoming) throws IOException {
        if (!incoming.request() || !incoming.event() || this.eventValue(incoming) != null) {
            throw new IOException("Only a heartbeat request has a heartbeat response");
        }
        return this.frame((byte) 0x22, (byte) 20, incoming.requestId(), new byte[] { 'N' });
    }

    public byte[] heartbeat(long requestId) throws IOException {
        return this.frame((byte) 0xe2, (byte) 0, requestId, new byte[] { 'N' });
    }

    public byte[] failure(DubboFrame incoming, Throwable failure) throws IOException {
        return this.error(incoming.requestId(), (byte) 40, failure.getMessage());
    }

    private byte[] error(long requestId, byte status, String message) throws IOException {
        DubboBoundedOutputStream bytes = new DubboBoundedOutputStream(this.maximum);
        Hessian2Output output = this.output(bytes);
        output.writeString(message == null ? "Dubbo invocation failed" : message);
        output.flush();
        return this.frame((byte) 2, status, requestId, bytes.toByteArray());
    }

    private byte[] frame(byte flags, byte status, long requestId, byte[] body) throws IOException {
        if (body.length > this.maximum) {
            throw new IOException("Dubbo body exceeds configured maxFrameSize");
        }
        DubboHessianValueGuard.validate(body);
        ByteBuffer output = ByteBuffer.allocate(16 + body.length);
        output.putShort((short) 0xdabb);
        output.put(flags);
        output.put(status);
        output.putLong(requestId);
        output.putInt(body.length);
        output.put(body);
        return output.array();
    }

    private Hessian2Output output(DubboBoundedOutputStream bytes) {
        Hessian2Output output = new Hessian2Output(bytes);
        output.setSerializerFactory(this.serializers);
        return output;
    }

    private Hessian2Input input(byte[] bytes) throws IOException {
        if (bytes.length > this.maximum) {
            throw new IOException("Dubbo body exceeds configured maxFrameSize");
        }
        DubboHessianValueGuard.validate(bytes);
        Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(bytes));
        input.setSerializerFactory(this.serializers);
        input.setRemoteResolver((name, address) -> {
            throw new IOException("Callable remote objects are not supported");
        });
        return input;
    }

    private Object readValue(Hessian2Input input, Class<?> type) throws IOException {
        Class<?> target = type == void.class ? Object.class : type;
        Object value = input.readObject(target);
        Class<?> expected = ClassUtils.primitiveToWrapper(target);
        if (value == null && target.isPrimitive()) {
            throw new IOException("Null Dubbo value cannot match a primitive type");
        }
        if (value != null && !expected.isInstance(value)) {
            throw new IOException("Dubbo value differs from published Java type");
        }
        return value;
    }

    private Map<?, ?> readAttachments(Hessian2Input input) throws IOException {
        Object attachments = input.readObject(Map.class);
        if (!(attachments instanceof Map<?, ?> map)) {
            throw new IOException("Dubbo attachments must be a map");
        }
        return map;
    }

    private void end(Hessian2Input input) throws IOException {
        if (input.read() != -1) {
            throw new IOException("Unexpected trailing Dubbo Hessian values");
        }
    }

    private void require(DubboFrame frame, boolean request) throws IOException {
        this.serialization(frame);
        if (frame.request() != request || frame.event()) {
            throw new IOException("Unexpected Dubbo frame direction or event");
        }
    }

    private void serialization(DubboFrame frame) throws IOException {
        if (frame.serializationId() != 2) {
            throw new IOException("Unsupported Dubbo serialization ID: " + frame.serializationId());
        }
    }

    private MetBindInfo<?> service(RequestPayload request) throws IOException {
        MetBindInfo<?> service = this.context.getServiceInfo(request.getServiceGroup(), //
                request.getServiceName(), request.getServiceVersion());
        if (service == null || !this.routes.accepts(service)) {
            throw new IOException("Unknown local Dubbo service: " + request.getServiceName());
        }
        return service;
    }

    private MetBindInfo<?> findService(String path, String group, String version) throws IOException {
        String selectedGroup = group == null ? "" : group;
        String selectedVersion = normalizeVersion(version);
        MetBindInfo<?> selected = null;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> candidate = this.context.getServiceInfo(id);
            boolean provider = candidate.getServiceType() == MetServiceType.Provider && !candidate.isShadow();
            if (!provider || !this.routes.accepts(candidate)) {
                continue;
            }
            if (!StringUtils.equals(path, this.alias(candidate))) {
                continue;
            }
            if (!StringUtils.equals(selectedGroup, this.wireGroup(candidate))) {
                continue;
            }
            if (!StringUtils.equals(selectedVersion, this.wireVersion(candidate))) {
                continue;
            }
            if (selected != null) {
                throw new IOException("Ambiguous Dubbo service identity: " + path);
            }
            selected = candidate;
        }
        if (selected == null) {
            throw new IOException("Unknown Dubbo service: " + path);
        }
        return selected;
    }

    private Method requestMethod(String path, String version, String name, String signature) throws IOException {
        String selectedVersion = normalizeVersion(version);
        Method selected = null;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> candidate = this.context.getServiceInfo(id);
            boolean provider = candidate.getServiceType() == MetServiceType.Provider && !candidate.isShadow();
            if (!provider || !this.routes.accepts(candidate)) {
                continue;
            }
            if (!StringUtils.equals(path, this.alias(candidate))) {
                continue;
            }
            if (!StringUtils.equals(selectedVersion, this.wireVersion(candidate))) {
                continue;
            }
            Method method;
            try {
                method = findMethod(candidate, name, signature);
            } catch (IOException ignored) {
                continue;
            }
            boolean consistent = selected == null;
            if (selected != null) {
                consistent = List.of(selected.getParameterTypes()).equals(List.of(method.getParameterTypes()));
            }
            if (!consistent) {
                throw new IOException("Dubbo service groups have inconsistent method signatures");
            }
            selected = method;
        }
        if (selected == null) {
            throw new IOException("Unknown Dubbo service method: " + path + "." + name);
        }
        return selected;
    }

    private String alias(MetBindInfo<?> service) {
        String alias = service.getAliasName(this.config.name());
        if (StringUtils.isBlank(alias)) {
            alias = service.getAliasName("dubbo");
        }
        return StringUtils.isBlank(alias) ? service.getBindType().getName() : alias;
    }

    private String wireGroup(MetBindInfo<?> service) {
        Object override = service.getMetaData("dubbo.registry.group");
        String value = override == null ? service.getBindGroup() : String.valueOf(override);
        return value == null ? "" : value;
    }

    private String wireVersion(MetBindInfo<?> service) {
        Object override = service.getMetaData("dubbo.registry.version");
        String value = override == null ? service.getBindVersion() : String.valueOf(override);
        return normalizeVersion(value);
    }

    private static String normalizeVersion(String value) {
        // 原生 Dubbo URL 可省略版本，但二进制调用统一使用 DEFAULT_VERSION。
        return StringUtils.isEmpty(value) ? "0.0.0" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<Method, String> methods(MetBindInfo<?> service) {
        return (Map<Method, String>) this.routes.binding(service).getDefinition();
    }

    private Method method(MetBindInfo<?> service, RequestPayload request) throws IOException {
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (!StringUtils.equals(method.getName(), request.getTargetMethod())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != request.getParameterTypes().size()) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < types.length; index++) {
                String provided = request.getParameterTypes().get(index);
                String javaName = types[index].getName();
                String asmName = MetRuntimeUtils.toAsmType(types[index]);
                matches &= StringUtils.equals(provided, javaName) || StringUtils.equals(provided, asmName);
            }
            if (matches) {
                return method;
            }
        }
        throw new IOException("Unknown Dubbo method: " + request.getTargetMethod());
    }

    private Method findMethod(MetBindInfo<?> service, String methodName, String signature) throws IOException {
        for (Map.Entry<Method, String> entry : this.methods(service).entrySet()) {
            Method method = entry.getKey();
            if (StringUtils.equals(methodName, entry.getValue()) && StringUtils.equals(signature, descriptor(method))) {
                return method;
            }
        }
        throw new IOException("Unknown Dubbo method or descriptor: " + methodName);
    }

    private Method genericMethod(MetBindInfo<?> service, String name, String[] names) throws IOException {
        for (Map.Entry<Method, String> entry : this.methods(service).entrySet()) {
            Method method = entry.getKey();
            if (!StringUtils.equals(name, entry.getValue()) || names.length != method.getParameterCount()) {
                continue;
            }
            boolean matches = true;
            Class<?>[] types = method.getParameterTypes();
            for (int index = 0; index < types.length; index++) {
                String requested = names[index];
                boolean same = StringUtils.equals(requested, types[index].getName());
                same |= StringUtils.equals(requested, types[index].getCanonicalName());
                matches &= same;
            }
            if (matches) {
                return method;
            }
        }
        throw new IOException("Unknown Dubbo generic method or parameter signature: " + name);
    }

    private Object realizeGeneric(Object value, JavaType declared, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("Dubbo generic value nesting exceeds 64 levels");
        }
        Class<?> type = declared.getRawClass();
        if (value == null) {
            if (type.isPrimitive()) {
                throw new IOException("Null Dubbo generic value cannot match a primitive type");
            }
            return null;
        }
        Class<?> boxed = ClassUtils.primitiveToWrapper(type);
        if (!declared.hasGenericTypes() && boxed.isInstance(value)) {
            return value;
        }

        // 容器本身已是 List/Map 不代表元素已经类型化，按本地方法泛型递归恢复。
        if (value instanceof Collection<?> source && declared.isCollectionLikeType()) {
            Collection<Object> result = JSON.convertValue(List.of(), declared);
            for (Object item : source) {
                result.add(this.realizeGeneric(item, declared.getContentType(), depth + 1));
            }
            return result;
        }
        if (value instanceof Map<?, ?> source && declared.isMapLikeType()) {
            Map<Object, Object> result = JSON.convertValue(Map.of(), declared);
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object key = this.realizeGeneric(entry.getKey(), declared.getKeyType(), depth + 1);
                Object item = this.realizeGeneric(entry.getValue(), declared.getContentType(), depth + 1);
                result.put(key, item);
            }
            return result;
        }
        if (value.getClass().isArray() && type.isArray()) {
            int length = Array.getLength(value);
            Object result = Array.newInstance(type.getComponentType(), length);
            for (int index = 0; index < length; index++) {
                Object item = this.realizeGeneric(Array.get(value, index), declared.getContentType(), depth + 1);
                Array.set(result, index, item);
            }
            return result;
        }

        // Map 中的 class 只是泛化数据，目标类型始终取自本地发布的方法。
        if (value instanceof Map<?, ?> fields && !Map.class.isAssignableFrom(type)) {
            ResolvableType owner = ServiceMetaParser.resolvableType(declared);
            Map<String, PropertyMeta> declarations = ServiceMetaParser.type(owner).getProperties();
            DubboBoundedOutputStream bytes = new DubboBoundedOutputStream(this.maximum);
            Hessian2Output output = this.output(bytes);
            output.writeMapBegin(type.getName());
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                String name = text(entry.getKey());
                PropertyMeta property = declarations.get(name);
                if (StringUtils.equals("class", name) || property == null || property.getField() == null) {
                    continue;
                }
                JavaType target = javaType(property.getType());
                Object item = this.realizeGeneric(entry.getValue(), target, depth + 1);
                output.writeObject(name);
                output.writeObject(item);
            }
            output.writeMapEnd();
            output.flush();
            return this.readValue(this.input(bytes.toByteArray()), type);
        }
        try {
            return JSON.convertValue(value, declared);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Dubbo generic argument differs from published type", failure);
        }
    }

    /** 公共描述已经解析继承类型变量，这里只转换为 Jackson 的容器类型。 */
    private static JavaType javaType(ResolvableType type) {
        if (type.isArray()) {
            return JSON.getTypeFactory().constructArrayType(javaType(type.getComponentType()));
        }
        Class<?> raw = type.resolve(Object.class);
        ResolvableType[] arguments = type.getGenerics();
        if (arguments.length == 0) {
            return JSON.getTypeFactory().constructType(raw);
        }
        JavaType[] generics = new JavaType[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            generics[index] = javaType(arguments[index]);
        }
        return JSON.getTypeFactory().constructParametricType(raw, generics);
    }

    private static Object generalize(Object value) {
        if (value == null) {
            return null;
        }
        boolean scalar = value instanceof String || value instanceof Number;
        scalar |= value instanceof Boolean || value instanceof Character || value instanceof Date;
        if (scalar) {
            return value;
        }
        if (value instanceof Enum<?> item) {
            return item.name();
        }
        if (value.getClass().isArray()) {
            if (value.getClass().getComponentType().isPrimitive()) {
                return value;
            }
            Object[] result = new Object[Array.getLength(value)];
            for (int index = 0; index < result.length; index++) {
                result[index] = generalize(Array.get(value, index));
            }
            return result;
        }
        if (value instanceof Collection<?> items) {
            List<Object> result = new ArrayList<>();
            for (Object item : items) {
                result.add(generalize(item));
            }
            return result;
        }
        if (value instanceof Map<?, ?> fields) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                result.put(generalize(entry.getKey()), generalize(entry.getValue()));
            }
            return result;
        }

        // 普通 DTO 使用 Jackson 属性映射，泛化接口以 Map 接收。
        Map<String, Object> fields = JSON.convertValue(value, Map.class);
        fields.put("class", value.getClass().getName());
        return fields;
    }

    private static String descriptor(Method method) {
        StringBuilder result = new StringBuilder();
        for (Class<?> type : method.getParameterTypes()) {
            appendDescriptor(result, type);
        }
        return result.toString();
    }

    private static void appendDescriptor(StringBuilder result, Class<?> type) {
        if (type.isArray()) {
            result.append('[');
            appendDescriptor(result, type.getComponentType());
        } else if (type.isPrimitive()) {
            result.append(MetRuntimeUtils.toAsmType(type));
        } else {
            result.append('L').append(type.getName().replace('.', '/')).append(';');
        }
    }

    private static Map<String, Object> attachments(MetOptionSet options) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String value = options.getOption(key);
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static void copyAttachments(Map<?, ?> attachments, MetOptionSet options) throws IOException {
        for (Map.Entry<?, ?> entry : attachments.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("Dubbo attachment key must be a string");
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IOException("Dubbo attachment value must be a scalar");
            }
            options.addOption(key, text(value));
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean supportsType(Class<?> type) {
        boolean stream = Flow.Publisher.class.isAssignableFrom(type) || InputStream.class.isAssignableFrom(type);
        stream |= OutputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        stream |= Writer.class.isAssignableFrom(type);
        if (stream) {
            return false;
        }
        if (type.isArray()) {
            return supportsType(type.getComponentType());
        }

        boolean container = Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
        return !type.isInterface() || container;
    }

    private static byte status(short value) {
        return switch (value) {
            case ProtocolStatus.NotFound -> 60;
            case ProtocolStatus.Timeout -> 31;
            case ProtocolStatus.QueueFull -> 100;
            case ProtocolStatus.Forbidden, ProtocolStatus.Unauthorized -> 40;
            default -> 70;
        };
    }

    private static short protocolStatus(byte value) {
        return switch (value) {
            case 30, 31 -> ProtocolStatus.Timeout;
            case 60 -> ProtocolStatus.NotFound;
            case 100 -> ProtocolStatus.QueueFull;
            case 40 -> ProtocolStatus.ProtocolError;
            default -> ProtocolStatus.InvokeError;
        };
    }
}
