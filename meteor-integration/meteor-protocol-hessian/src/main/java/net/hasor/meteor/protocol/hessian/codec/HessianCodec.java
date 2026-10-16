/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import com.caucho.hessian.io.*;
import com.caucho.services.server.AbstractSkeleton;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.EConsumer;
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

/** HTTP 服务地址与 Hessian 1/2 调用映射；对象图交给 Caucho 原生序列化处理。 */
public final class HessianCodec {
    private final ProtocolConfig    protocol;
    private final MetContext        context;
    private final SerializerFactory serializers;
    private final int               version;
    private final int               maxMessageSize;

    public HessianCodec(ProtocolConfig protocol, MetContext context) {
        this.protocol = protocol;
        this.context = context;
        this.serializers = new HessianSerializerFactory(context.getClassLoader());
        this.version = protocol.integer("version", 2);
        this.maxMessageSize = protocol.integer("maxMessageSize", 4 * 1024 * 1024);
        if (this.version != 1 && this.version != 2) {
            throw new IllegalArgumentException("Hessian version must be 1 or 2");
        }
        if (this.maxMessageSize <= 0) {
            throw new IllegalArgumentException("maxMessageSize must be positive");
        }
    }

    /** Hessian 1 call 的版本二头表示使用 Hessian 2 reply，调用正文仍是 Hessian 1。 */
    public int responseVersion(byte[] body) throws IOException {
        if (body == null || body.length < 3) {
            throw new IOException("Invalid Hessian call version header");
        }
        int marker = body[0] & 0xff;
        int major = body[1] & 0xff;
        int minor = body[2] & 0xff;
        // 官方无参 startCall 使用历史版本头 c 0 1，正文和回复仍使用 Hessian 1。
        if (marker == 'c' && major == 0 && minor == 1) {
            return 1;
        }
        if (minor != 0) {
            throw new IOException("Invalid Hessian call version header");
        }
        if (marker == 'c' && (major == 1 || major == 2)) {
            return major;
        }
        if (marker == 'H' && major == 2) {
            return 2;
        }
        throw new IOException("Unsupported Hessian call version");
    }

    public RequestPayload decodeRequest(byte[] body, String uri, RouteMount mount) throws IOException {
        // 先验证完整文档的语法与长度，再让原生库创建对象。
        this.responseVersion(body);
        HessianWireGuard.validate(body, this.maxMessageSize);
        MetBindInfo<?> service = this.provider(uri, mount);
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        AbstractHessianInput input;
        if (body[0] == 'H') {
            bytes.skip(3);
            input = new Hessian2Input(bytes);
        } else {
            input = new HessianInput(bytes);
        }
        this.configure(input);
        try {
            input.readCall();
            while (input.readHeader() != null) {
                input.readObject();
            }
            String name = input.readMethod();
            int argumentCount = input.readMethodArgLength();
            Method method = this.resolve(service, name, argumentCount);

            // 按已发布 Java 签名读取参数，保留原生对象引用与自引用图。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup(service.getBindGroup());
            request.setServiceName(service.getBindName());
            request.setServiceVersion(service.getBindVersion());
            request.setTargetMethod(method.getName());
            request.setSerializeType("Hessian");
            request.setMessage(false);
            request.setReceiveTime(System.currentTimeMillis());
            request.setClientTimeout(this.context.getSettings().getDefaultTimeout());
            for (Class<?> type : method.getParameterTypes()) {
                request.addParameter(type.getName(), input.readObject(type));
            }
            input.completeCall();
            this.end(input, bytes);
            return request;
        } finally {
            input.close();
        }
    }

    public byte[] encodeRequest(RequestPayload request) throws IOException {
        if (request.isMessage()) {
            throw new IOException("Hessian does not support message calls");
        }
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(request, service);
        String name = method.getName();
        int overloads = 0;
        for (Method candidate : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (StringUtils.equals(candidate.getName(), name) && !candidate.isBridge() && !candidate.isSynthetic()) {
                overloads++;
            }
        }
        if (overloads > 1) {
            name = AbstractSkeleton.mangleName(method, false);
        }

        // 非重载方法使用普通名称；重载名称遵循官方 Java 客户端的 mangling。
        String wireMethod = name;
        Object[] parameters = request.getParameterValues().toArray();
        return this.encode(this.version, output -> output.call(wireMethod, parameters));
    }

    public String requestPath(RequestPayload request) {
        MetBindInfo<?> service = this.service(request);
        if (!this.protocol.exactPaths(service).isEmpty()) {
            return this.protocol.contextPath(service);
        }
        return this.protocol.requestPath(service, alias(service));
    }

    public byte[] encodeResponse(ResponsePayload response, int version) throws IOException {
        if (response.getStatus() != ProtocolStatus.OK) {
            Throwable failure = response.getFailure();
            if (failure == null) {
                String message = response.getOption("message");
                failure = new HessianFaultException("ServiceException", message);
            }
            return this.encodeError(failure, version);
        }
        return this.encode(version, output -> output.writeReply(response.getReturnData()));
    }

    public byte[] encodeError(Throwable failure, int version) throws IOException {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String code = "ServiceException";
        Object detail = cause;
        if (cause instanceof HessianFaultException fault) {
            code = fault.getCode();
            detail = null;
        }
        String message = cause == null ? "Hessian service failed" : cause.getMessage();
        String faultCode = code;
        Object faultDetail = detail;
        return this.encode(version, output -> output.writeFault(faultCode, message, faultDetail));
    }

    public ResponsePayload decodeResponse(RequestPayload request, byte[] body) throws Throwable {
        // Guard 验证整个正文，也覆盖原生库在 fault 分支未消费外层终止符的情况。
        HessianWireGuard.validate(body, this.maxMessageSize);
        boolean hessian2 = body[0] == 'H';
        boolean hessian1 = body[0] == 'r';
        if (!hessian1 && !hessian2) {
            throw new IOException("Expected Hessian reply");
        }
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        AbstractHessianInput input;
        if (hessian2) {
            bytes.skip(3);
            input = new Hessian2Input(bytes);
        } else {
            input = new HessianInput(bytes);
        }
        this.configure(input);
        try {
            Method method = this.method(request, this.service(request));
            Class<?> resultType = method.getReturnType();
            Class<?> expected = resultType == void.class ? Object.class : resultType;
            Object value = input.readReply(expected);
            this.end(input, bytes);

            // 原始 fault 由 readReply 抛出，交给会话发送 ThrowPayload，保留业务异常类型。
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(request.getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setSerializeType("Hessian");
            response.setReceiveTime(System.currentTimeMillis());
            response.setReturnType(resultType.getName());
            response.setReturnData(value);
            return response;
        } finally {
            input.close();
        }
    }

    /** 发布时验证服务签名与官方重载命名，避免运行期出现不可区分的方法。 */
    public static void validateService(ProtocolConfig protocol, MetBindInfo<?> service) {
        if (!protocol.accepts(service.getBindProtocols())) {
            return;
        }
        String alias = alias(service);
        boolean relativePath = StringUtils.equals(alias, ".") || StringUtils.equals(alias, "..");
        if (relativePath || StringUtils.isBlank(alias) || !alias.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Hessian service alias: " + alias);
        }
        if (service.isMessage()) {
            throw new IllegalArgumentException("Hessian does not support message services");
        }
        Map<String, Method> names = new LinkedHashMap<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (method.isAnnotationPresent(MetMessage.class)) {
                throw new IllegalArgumentException("Hessian does not support message method: " + method.getName());
            }
            rejectStream(method.getReturnType());
            for (Class<?> type : method.getParameterTypes()) {
                rejectStream(type);
            }
            String mangled = AbstractSkeleton.mangleName(method, false);
            Method previous = names.putIfAbsent(mangled, method);
            if (previous != null && !previous.equals(method)) {
                throw new IllegalArgumentException("Ambiguous Hessian method signature: " + mangled);
            }
        }
    }

    private byte[] encode(int version, EConsumer<AbstractHessianOutput, IOException> action) throws IOException {
        HessianBoundedOutputStream bytes = new HessianBoundedOutputStream(this.maxMessageSize);
        AbstractHessianOutput output;
        if (version == 1) {
            output = new HessianOutput(bytes);
        } else if (version == 2) {
            output = new Hessian2Output(bytes);
        } else {
            throw new IOException("Unsupported Hessian reply version");
        }
        output.setSerializerFactory(this.serializers);
        try {
            action.eAccept(output);
            output.flush();
            byte[] body = bytes.bytes();
            HessianWireGuard.validate(body, this.maxMessageSize);
            return body;
        } finally {
            output.close();
        }
    }

    private void configure(AbstractHessianInput input) {
        input.setSerializerFactory(this.serializers);
        input.setRemoteResolver((type, url) -> {
            throw new IOException("Callable remote objects are not supported");
        });
    }

    private void end(AbstractHessianInput input, ByteArrayInputStream bytes) throws IOException {
        int remaining;
        if (input instanceof Hessian2Input hessian2) {
            remaining = hessian2.read();
        } else {
            remaining = bytes.read();
        }
        if (remaining != -1) {
            throw new IOException("Unexpected trailing Hessian data");
        }
    }

    private MetBindInfo<?> provider(String uri, RouteMount mount) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        MetBindInfo<?> selected = null;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            boolean provider = service != null && service.getServiceType() == MetServiceType.Provider;
            if (!provider || service.isShadow() || !this.protocol.accepts(service, mount)) {
                continue;
            }
            String endpoint = mount.exact() ? mount.path() : mount.prefix() + alias(service);
            if (!StringUtils.equals(path, endpoint)) {
                continue;
            }
            if (selected != null) {
                throw new HessianFaultException("NoSuchObjectException", "Ambiguous Hessian service endpoint");
            }
            selected = service;
        }
        if (selected == null) {
            throw new HessianFaultException("NoSuchObjectException", "Hessian service not found");
        }
        return selected;
    }

    private Method resolve(MetBindInfo<?> service, String name, int argumentCount) {
        Method selected = null;
        boolean ambiguous = false;
        for (Method candidate : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (candidate.isBridge() || candidate.isSynthetic()) {
                continue;
            }
            if (argumentCount >= 0 && argumentCount != candidate.getParameterCount()) {
                continue;
            }

            // 零参数重载的标准名称与普通方法名相同，优先选择完全匹配的 mangling。
            String mangled = AbstractSkeleton.mangleName(candidate, false);
            if (StringUtils.equals(name, mangled)) {
                return candidate;
            }
            if (StringUtils.equals(name, candidate.getName())) {
                ambiguous |= selected != null;
                selected = candidate;
            }
        }
        if (ambiguous) {
            throw new HessianFaultException("NoSuchMethodException", "Ambiguous overloaded Hessian method");
        }
        if (selected == null) {
            throw new HessianFaultException("NoSuchMethodException", "Hessian method not found: " + name);
        }
        return selected;
    }

    private Method method(RequestPayload request, MetBindInfo<?> service) throws IOException {
        Class<?>[] types = new Class<?>[request.getParameterTypes().size()];
        try {
            for (int index = 0; index < types.length; index++) {
                String name = request.getParameterTypes().get(index);
                types[index] = MetRuntimeUtils.toJavaType(name, this.context.getClassLoader());
            }
            return MetRuntimeUtils.getServiceMethod(service.getBindType(), request.getTargetMethod(), types);
        } catch (Exception failure) {
            throw new IOException("Hessian consumer method is unavailable", failure);
        }
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        MetBindInfo<?> service = this.context.getServiceInfo(group, name, request.getServiceVersion());
        if (service == null) {
            throw new HessianFaultException("NoSuchObjectException", "Hessian service is unavailable");
        }
        return service;
    }

    private static String alias(MetBindInfo<?> service) {
        String alias = service.getAliasName("hessian");
        return StringUtils.isBlank(alias) ? service.getBindName() : alias;
    }

    private static void rejectStream(Class<?> type) {
        boolean publisher = Flow.Publisher.class.isAssignableFrom(type);
        boolean input = InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        boolean output = OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type);
        if (publisher || input || output) {
            throw new IllegalArgumentException("Hessian does not support streaming type: " + type.getName());
        }
    }
}
