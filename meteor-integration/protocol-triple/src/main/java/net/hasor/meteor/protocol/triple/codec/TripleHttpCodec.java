/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.binding.GrpcServiceBinding;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;

/** Triple 的 HTTP、gRPC 和 POJO wrapper 消息映射，不持有调用或网络生命周期。 */
public final class TripleHttpCodec {
    private static final ObjectMapper     JSON     = JsonMapper.builder() //
            .serializationInclusion(JsonInclude.Include.NON_NULL) //
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) //
            .build();
    private static final Set<String>      RESERVED = Set.of("content-type", "content-length", "content-encoding", //
            "accept-encoding", "host", "connection", "transfer-encoding", "te", "user-agent", "message");
    private final        ProtocolConfig   config;
    private final        RouteMountIndex  routes;
    private final        MetContext       context;
    private final        TripleFrameCodec frames;
    private final        TripleValueCodec values;
    private final        GrpcHttpCodec    grpc;
    private final        String           mode;
    private final        String           compression;

    public TripleHttpCodec(ProtocolConfig config, RouteMountIndex routes, MetContext context) {
        this.config = config;
        this.routes = routes;
        this.context = context;
        int maximum = config.integer("maxMessageSize", 4 * 1024 * 1024);
        this.frames = new TripleFrameCodec(maximum);
        this.values = new TripleValueCodec(maximum, context.getClassLoader());
        this.grpc = new GrpcHttpCodec(config, routes, context);
        this.mode = config.option("httpMode", "grpc").toLowerCase(Locale.ROOT);
        if (!Set.of("grpc", "json", "proto").contains(this.mode)) {
            throw new IllegalArgumentException("Triple httpMode must be grpc, json or proto");
        }
        this.compression = encoding(config.option("compression", "identity"));
        int version = this.httpVersion();
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("Triple httpVersion must be 1 or 2");
        }
        if (StringUtils.equals(this.mode, "grpc") && version != 2) {
            throw new IllegalArgumentException("Triple gRPC framing requires HTTP/2");
        }
    }

    /** 准备端点发布绑定；普通 Java POJO 不要求 proto，IDL 模式复用已有绑定资源。 */
    public static ProtocolBinding prepareBinding(ProtocolConfig config, ProtocolMeta meta) {
        MetBindInfo<?> service = meta.getService();
        String alias = service.getAliasName(config.name());
        if (StringUtils.isBlank(alias) || alias.indexOf('/') >= 0 || alias.indexOf('?') >= 0) {
            return null;
        }
        String mode = config.option("httpMode", "grpc");
        String coding = service.getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", "Hessian2");
        }
        boolean hessian = StringUtils.equalsIgnoreCase(coding, "Hessian2");
        boolean json = StringUtils.equalsIgnoreCase(coding, "Json");
        boolean protobuf = StringUtils.equalsIgnoreCase(coding, "Protobuf");
        if (!hessian && !json && !protobuf) {
            return null;
        }
        Object resource = meta.getBindResource();
        if (resource != null && !(resource instanceof GrpcProtoResource)) {
            throw new IllegalArgumentException("Triple requires a GrpcProtoResource");
        }

        // 原生 Protobuf/ProtoJSON 模式使用 gRPC 家族共同的定义及 Java 映射。
        if (resource instanceof GrpcProtoResource || protobuf) {
            if (hessian) {
                return null;
            }
            GrpcServiceBinding binding = GrpcServiceBinding.prepare(config, meta, "Hessian2");
            if (binding == null) {
                return null;
            }
            if (!StringUtils.equalsIgnoreCase(mode, "grpc")) {
                for (Method method : binding.methods()) {
                    if (binding.shape(method).streaming()) {
                        return null;
                    }
                }
            }

            // 已编译定义同时决定端点契约描述，失败时不产生半成品绑定。
            ObjectNode description = meta.createServiceDescription(config, "Hessian2");
            binding.describeContract(description);
            return meta.build(config, binding, description);
        }
        if (StringUtils.equalsIgnoreCase(mode, "proto") //
                || StringUtils.equalsIgnoreCase(mode, "grpc") && !hessian) {
            return null;
        }

        // Java POJO 包装与普通 JSON 继续使用原来的签名规则，允许按参数类型区分重载。
        Set<String> signatures = new HashSet<>();
        Map<Method, String> names = new LinkedHashMap<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            MethodMeta description = ServiceMetaParser.method(service.getBindType(), method);
            if (!TripleMethodShape.supports(description) || service.isMessage() || description.isMessage()) {
                return null;
            }
            TripleMethodShape shape = new TripleMethodShape(description);
            if (!StringUtils.equalsIgnoreCase(mode, "grpc") && shape.streaming()) {
                return null;
            }
            String name = MetRuntimeUtils.getProtocolMethodName(method);
            List<String> argumentTypes = new ArrayList<>();
            if (shape.streamingRequest()) {
                argumentTypes.add(shape.requestType().getName());
            } else {
                for (Class<?> type : method.getParameterTypes()) {
                    argumentTypes.add(type.getName());
                }
            }
            String signature = name + argumentTypes;
            if (!signatures.add(signature)) {
                return null;
            }
            names.put(method, name);
        }

        // POJO 分支按实际方法名称导出描述，和原生 Protobuf 分支共享提交语义。
        Map<Method, String> operations = Collections.unmodifiableMap(names);
        ObjectNode description = meta.createServiceDescription(config, "Hessian2", operations);
        return meta.build(config, operations, description);
    }

    public RequestPayload open(HttpRequest input) throws Exception {
        if (!StringUtils.equals(input.method(), "POST")) {
            throw new TripleHttpStatusException(405, "Triple requires POST");
        }
        String path = input.uri();
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw status(3, "Triple RPC path must not contain a query or fragment");
        }
        boolean framed = this.framed(input);
        if (framed && input.version() != 2) {
            throw status(12, "Triple gRPC framing requires HTTP/2");
        }
        RouteMount mount = this.mount(input);
        if (mount == null || path.length() <= mount.prefix().length()) {
            throw status(12, "Triple path is outside its mount");
        }
        String[] parts = path.substring(mount.prefix().length()).split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw status(3, "Expected /service/method");
        }
        MetBindInfo<?> service = this.findService(parts[0], input, mount);
        Method target = this.remoteMethod(service, parts[1]);
        String coding = this.coding(input, service);
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(target.getName());
        request.addOption("triple.remote.method", parts[1]);
        this.rememberMethod(request, target);
        request.setSerializeType(coding);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.timeout(input, framed));
        this.readHeaders(input.headers(), request);
        request.addOption("grpc-triple-http-mode", framed ? "grpc" : this.plainMode(input));
        String compressionHeader = framed ? "grpc-encoding" : "content-encoding";
        encoding(input.header(compressionHeader));
        String accept = input.header(framed ? "grpc-accept-encoding" : "accept-encoding");
        request.addOption("grpc-accept-encoding", accept);
        if (!framed && this.shape(request).streaming()) {
            throw status(12, "Triple plain HTTP supports unary methods only");
        }
        return request;
    }

    public boolean framed(HttpRequest request) {
        String type = media(request.header("content-type"));
        return StringUtils.equals(type, "application/grpc") || type.startsWith("application/grpc+") //
                || StringUtils.equals(type, "application/triple+wrapper");
    }

    public boolean framed(RequestPayload request) {
        return StringUtils.equals(this.requestMode(request), "grpc");
    }

    public boolean wrapped(RequestPayload request) {
        return this.framed(request) && this.definition(this.service(request)) == null;
    }

    public TripleMethodShape shape(RequestPayload request) {
        return shape(this.service(request), this.target(request));
    }

    private static TripleMethodShape shape(MetBindInfo<?> service, Method method) {
        return new TripleMethodShape(ServiceMetaParser.method(service.getBindType(), method));
    }

    public Method target(RequestPayload request) {
        String encoded = request.getOption("grpc-triple-method-types");
        List<String> types = request.getParameterTypes();
        if (encoded != null) {
            try {
                types = Arrays.asList(JSON.readValue(encoded, String[].class));
            } catch (IOException invalid) {
                throw new IllegalStateException("Invalid resolved Triple signature", invalid);
            }
        }
        return this.requestMethod(request, types);
    }

    /** 仅重载的流形状不同时，需要读取首个 wrapper 才能决定生命周期分支。 */
    public boolean needsMethodResolution(RequestPayload request) {
        if (!this.wrapped(request)) {
            return false;
        }
        Set<String> shapes = new HashSet<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(this.service(request).getBindType())) {
            if (StringUtils.equals(method.getName(), request.getTargetMethod())) {
                TripleMethodShape shape = shape(this.service(request), method);
                shapes.add(shape.streamingRequest() + ":" + shape.streamingResponse());
            }
        }
        return shapes.size() > 1;
    }

    public void resolveRequestMethod(RequestPayload request, byte[] bytes) {
        if (bytes == null) {
            for (Method method : MetRuntimeUtils.getServiceMethods(this.service(request).getBindType())) {
                boolean named = StringUtils.equals(method.getName(), request.getTargetMethod());
                boolean streaming = shape(this.service(request), method).streamingRequest();
                if (named && streaming) {
                    this.rememberMethod(request, method);
                    return;
                }
            }
            throw status(13, "Triple request contains no message");
        }
        List<String> types = this.values.argumentTypes(bytes);
        Method method = this.requestMethod(request, types);
        this.rememberMethod(request, method);
    }

    private void rememberMethod(RequestPayload request, Method method) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : method.getParameterTypes()) {
            names.add(type.getName());
        }
        try {
            request.addOption("grpc-triple-method-types", JSON.writeValueAsString(names));
        } catch (IOException unexpected) {
            throw new IllegalStateException("Cannot remember resolved Triple signature", unexpected);
        }
    }

    public void readRequest(RequestPayload request, HttpRequest input) throws Exception {
        byte[] bytes;
        if (this.framed(request)) {
            bytes = this.readRequestMessage(request, input);
        } else {
            bytes = this.frames.decompress(input.body(), input.header("content-encoding"));
        }
        this.decodeRequestValue(request, bytes);
    }

    private byte[] readRequestMessage(RequestPayload request, HttpRequest input) throws IOException {
        // 标准调用和流式方法保持单消息解帧；Beta 方言只用于已有 proto 的普通 Protobuf 请求。
        String encoding = input.header("grpc-encoding");
        boolean protobuf = StringUtils.equals(request.getSerializeType(), "Protobuf") && !this.wrapped(request);
        boolean dataMedia = StringUtils.equals(media(input.header("content-type")), "application/grpc+data");
        if (!protobuf || !dataMedia || this.shape(request).streaming()) {
            return this.frames.unframe(input.body(), encoding);
        }

        // Apache Python 3.0.0b1 将 done_writing 编码为额外的五零字节帧，且该帧必须位于正文末尾。
        ByteArrayInputStream body = new ByteArrayInputStream(input.body());
        byte[] message = this.frames.readMessage(body, encoding);
        if (message == null) {
            throw status(13, "Expected exactly one Triple message");
        }

        int remaining = body.available();
        if (remaining == 0) {
            return message;
        }

        if (remaining == 5) {
            boolean terminator = true;
            while (body.available() > 0) {
                terminator &= body.read() == 0;
            }

            if (terminator) {
                return message;
            }
        }
        throw status(13, "Expected exactly one Triple message");
    }

    /** wrapper 自带参数签名；先选择已发布的方法，再按其真实类型反序列化。 */
    public void decodeRequestValue(RequestPayload request, byte[] bytes) throws Exception {
        Method method;
        Object[] arguments;
        if (this.wrapped(request)) {
            List<String> types = this.values.argumentTypes(bytes);
            method = this.requestMethod(request, types);
            arguments = this.values.decodeArguments(bytes, method);
        } else if (StringUtils.equals(this.requestMode(request), "json")) {
            this.frames.check(bytes);
            JsonNode input = JSON.readTree(bytes);
            if (input == null || input.isMissingNode()) {
                throw status(3, "Triple JSON request requires a complete document");
            }
            method = this.jsonMethod(request, input);
            arguments = this.jsonArguments(input, method);
            if (this.definition(this.service(request)) != null) {
                // 严格文档及参数校验已完成，IDL 绑定继续使用原字节，保留整数词法 -0。
                byte[] encoded = bytes;
                if (input.isArray()) {
                    try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
                        parser.nextToken();
                        parser.nextToken();
                        int start = (int) parser.currentTokenLocation().getByteOffset();
                        parser.skipChildren();
                        parser.finishToken();
                        int end = (int) parser.currentLocation().getByteOffset();
                        encoded = Arrays.copyOfRange(bytes, start, end);
                    }
                }
                arguments[0] = this.nativeDecode(request, method.getParameterTypes()[0], encoded, true);
            }
        } else {
            method = this.target(request);
            Class<?> type = method.getParameterTypes()[0];
            arguments = new Object[] { this.decodeValue(request, type, bytes, true) };
        }
        this.rememberMethod(request, method);
        for (int index = 0; index < arguments.length; index++) {
            request.addParameter(method.getParameterTypes()[index].getName(), arguments[index]);
        }
    }

    public HttpRequest encode(RequestPayload request) throws Exception {
        if (request.isMessage()) {
            throw status(3, "Triple requires a response");
        }
        if (this.shape(request).streaming()) {
            throw status(3, "Streaming methods require incremental HTTP bodies");
        }
        byte[] body = this.encodeValue(request, request.getParameterValues().toArray(), true);
        body = this.framed(request) ? this.frame(body, this.compression) : this.frames.compress(body, this.compression);
        return HttpRequest.newBuffered("POST", this.path(request), this.requestHeaders(request), body, //
                this.httpVersion(), Map.of());
    }

    public HttpRequest streamRequest(RequestPayload request, HttpBodyStream body) {
        if (!this.framed(request)) {
            throw status(12, "Triple plain HTTP supports unary methods only");
        }
        return HttpRequest.newStreaming("POST", this.path(request), this.requestHeaders(request), body, 2);
    }

    public byte[] encodeValue(RequestPayload request, Object value, boolean parameter) throws Exception {
        TripleMethodShape shape = this.shape(request);
        if (this.wrapped(request)) {
            if (!StringUtils.equalsIgnoreCase(request.getSerializeType(), "Hessian2")) {
                throw status(12, "Triple gRPC POJO wrapper requires Hessian2");
            }
            if (!parameter) {
                return this.values.encodeResult(value, shape.responseType());
            }
            if (shape.streamingRequest()) {
                return this.values.encodeArguments(new Object[] { value }, new Class<?>[] { shape.requestType() });
            }
            return this.values.encodeArguments((Object[]) value, shape.method().getParameterTypes());
        }
        if (this.definition(this.service(request)) != null) {
            Object single = parameter && !shape.streamingRequest() ? ((Object[]) value)[0] : value;
            byte[] encoded = this.nativeEncode(request, single, parameter);
            if (parameter && StringUtils.equals(this.requestMode(request), "json")) {
                JsonNode argument = JSON.readTree(encoded);
                encoded = JSON.writeValueAsBytes(List.of(argument));
                this.frames.check(encoded);
            }
            return encoded;
        }
        byte[] bytes = JSON.writeValueAsBytes(value);
        this.frames.check(bytes);
        return bytes;
    }

    public Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, boolean parameter) throws Exception {
        if (this.wrapped(request)) {
            if (parameter) {
                return this.values.decodeArguments(bytes, new Class<?>[] { type })[0];
            }
            return this.values.decodeResult(bytes, type);
        }
        if (this.definition(this.service(request)) != null) {
            return this.nativeDecode(request, type, bytes, parameter);
        }
        if (type == void.class || type == Void.class) {
            this.frames.check(bytes);
            JsonNode value = JSON.readTree(bytes);
            if (!value.isNull()) {
                throw status(13, "Triple void response must be null");
            }
            return null;
        }
        this.frames.check(bytes);
        return JSON.readValue(bytes, type);
    }

    public HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
        Throwable failure = response.getFailure();
        if (failure == null && response.getStatus() != ProtocolStatus.OK) {
            failure = new MetException(response.getStatus(), response.getOption("message"));
        }
        if (failure != null) {
            boolean unaryWrapper = this.wrapped(request) && !this.shape(request).streaming();
            boolean invocationFailed = response.getStatus() == ProtocolStatus.InvokeError;
            boolean businessFailure = response.getFailure() != null && GrpcStatusException.find(failure) == null;
            boolean business = unaryWrapper && invocationFailed && businessFailure;
            if (business) {
                byte[] value = this.values.encodeResult(failure, this.shape(request).responseType());
                byte[] body = this.frame(value, this.responseCompression(request));
                Map<String, List<String>> headers = this.responseHeaders(request, response);
                headers.put("tri-exception-code", List.of("3"));
                return HttpResponse.newBuffered(200, headers, body, this.responseTrailers(response));
            }
            return this.error(request, failure, response);
        }
        byte[] body = this.encodeValue(request, response.getReturnData(), false);
        String encoding = this.responseCompression(request);
        body = this.framed(request) ? this.frame(body, encoding) : this.frames.compress(body, encoding);
        Map<String, List<String>> trailers = this.framed(request) ? this.responseTrailers(response) : Map.of();
        return HttpResponse.newBuffered(200, this.responseHeaders(request, response), body, trailers);
    }

    public ResponsePayload decode(RequestPayload request, HttpResponse input) throws Exception {
        this.validateStatus(input, input.trailers());
        this.validateResponseHeaders(request, input);
        byte[] bytes = this.framed(request) ? this.frames.unframe(input.body(), input.header("grpc-encoding")) //
                : this.frames.decompress(input.body(), input.header("content-encoding"));
        ResponsePayload response = new ResponsePayload();
        this.readHeaders(input.headers(), response);
        this.readTrailers(input.trailers(), response);
        String exceptionCode = input.header("tri-exception-code");
        boolean noException = exceptionCode == null || StringUtils.equals(exceptionCode, "0");
        if (this.wrapped(request) && !noException) {
            Object failure = this.values.decodeResult(bytes, Exception.class);
            if (failure instanceof Exception exception) {
                this.validateExceptionCode(exceptionCode, exception, response);
                throw exception;
            }
            throw status(13, "Invalid Triple exception wrapper");
        }
        Class<?> type = this.shape(request).responseType();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(request.getSerializeType());
        response.setReturnType(type.getName());
        response.setReturnData(this.decodeValue(request, type, bytes, false));
        return response;
    }

    private void validateExceptionCode(String value, Exception cause, ResponsePayload response) {
        int code;
        try {
            code = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw status(13, "Invalid Triple exception code");
        }
        if (code < 1 || code > 16) {
            throw status(13, "Invalid Triple exception code");
        }
        if (code == 3 && !(cause instanceof TimeoutException)) {
            // Dubbo 以 3 标记业务异常，保留已发布业务异常的 Java 类型。
            return;
        }
        if (code == 3) {
            // 部分 Dubbo HTTP 期限路径仍标记业务异常，已识别的期限类型恢复标准状态。
            code = 4;
        }

        // 其余编号沿用 gRPC 状态，不能依赖客户端是否安装了 Dubbo 的异常类型。
        String message = cause.getMessage();
        if (message == null) {
            message = code == 4 ? "Remote Triple deadline exceeded" : "Remote Triple call failed";
        }
        GrpcMetadata metadata = GrpcMetadata.from(response);
        GrpcStatusException failure = new GrpcStatusException(code, message, null, metadata);
        failure.initCause(cause);
        throw failure;
    }

    public Map<String, List<String>> responseHeaders(RequestPayload request, ResponsePayload response) {
        Map<String, List<String>> headers = response == null ? new LinkedHashMap<>() : this.metadata(response);
        if (response != null) {
            headers.putAll(GrpcMetadata.from(response).getHeaders());
        }
        headers.put("content-type", List.of(this.contentType(request)));
        headers.put(this.framed(request) ? "grpc-encoding" : "content-encoding", //
                List.of(this.responseCompression(request)));
        headers.put(this.framed(request) ? "grpc-accept-encoding" : "accept-encoding", List.of("identity,gzip"));
        return headers;
    }

    public Map<String, List<String>> responseTrailers(ResponsePayload response) {
        Map<String, List<String>> trailers = new LinkedHashMap<>(this.grpc.responseTrailers(response));
        // 内部异常说明可能包含 Unicode，业务异常正文已保留它，不能原样写入 HTTP 字段。
        trailers.remove("message");
        return trailers;
    }

    public void validateStatus(HttpResponse input, Map<String, List<String>> trailers) throws IOException {
        if (media(input.header("content-type")).startsWith("application/grpc") //
                || input.header("grpc-status") != null || trailers.containsKey("grpc-status")) {
            this.grpc.validateStatus(input, trailers);
        } else if (input.status() != 200) {
            int code = switch (input.status()) {
                case 400 -> 3;
                case 401 -> 16;
                case 403 -> 7;
                case 404, 405 -> 12;
                case 408 -> 4;
                case 409 -> 10;
                case 412 -> 9;
                case 413, 431 -> 8;
                case 429, 502, 503, 504 -> 14;
                default -> 2;
            };
            try {
                int maximum = this.config.integer("maxMessageSize", 4 * 1024 * 1024);
                byte[] encoded = input.stream() == null ? input.body() : input.stream().readNBytes(maximum + 1);
                byte[] bytes = this.frames.decompress(encoded, input.header("content-encoding"));
                JsonNode error = JSON.readTree(bytes);
                JsonNode status = error == null ? null : error.get("status");
                JsonNode message = error == null ? null : error.get("message");
                int remoteCode = status != null && status.isInt() ? status.intValue() : -1;
                boolean validCode = remoteCode > 0 && remoteCode <= 16;
                boolean validMessage = message != null && message.isTextual();
                if (validCode && validMessage) {
                    throw status(status.intValue(), message.textValue());
                }
            } catch (GrpcStatusException failure) {
                throw failure;
            } catch (IOException | IllegalArgumentException malformed) {
                // 未提供合法错误正文的 HTTP 对端仍按其 HTTP 状态恢复协议错误。
            }
            throw status(code, "Triple HTTP " + input.status());
        }
    }

    public void validateResponseHeaders(RequestPayload request, HttpResponse input) throws IOException {
        String media = media(input.header("content-type"));
        String expected = this.contentType(request);
        boolean grpcMedia = StringUtils.equals(media, "application/grpc");
        boolean protobuf = StringUtils.equals(expected, "application/grpc+proto");
        boolean implicitProto = grpcMedia && protobuf;
        // Python SDK 的 +data 携带原生消息，仅允许已绑定 proto 的 Protobuf 调用使用该别名。
        boolean protobufData = protobuf && !this.wrapped(request) //
                && StringUtils.equals(media, "application/grpc+data");
        if (!implicitProto && !protobufData && !StringUtils.equals(media, expected)) {
            throw status(13, "Triple response content-type differs from request");
        }
        encoding(input.header(this.framed(request) ? "grpc-encoding" : "content-encoding"));
    }

    public HttpResponse error(Throwable failure) {
        return this.grpc.error(failure);
    }

    public HttpResponse error(Throwable failure, ResponsePayload response) {
        return this.grpc.error(failure, response);
    }

    public HttpResponse error(HttpRequest request, Throwable failure) {
        boolean httpRejection = failure instanceof TripleHttpStatusException;
        return !httpRejection && this.framed(request) ? this.grpc.error(failure) : this.httpError(failure, null);
    }

    public HttpResponse error(RequestPayload request, Throwable failure, ResponsePayload response) {
        return this.framed(request) ? this.grpc.error(failure, response) : this.httpError(failure, response);
    }

    public HttpResponse trailersOnly(HttpResponse response) {
        return this.grpc.trailersOnly(response);
    }

    public byte[] frame(byte[] message, String encoding) throws IOException {
        return this.frames.frame(message, encoding);
    }

    public byte[] readMessage(InputStream input, String encoding) throws IOException {
        return this.frames.readMessage(input, encoding);
    }

    public String requestCompression() {
        return this.compression;
    }

    public String responseCompression(RequestPayload request) {
        return this.grpc.responseCompression(request);
    }

    public void readHeaders(Map<String, List<String>> fields, MetOptionSet options) {
        this.grpc.readHeaders(fields, options);
    }

    public void readTrailers(Map<String, List<String>> fields, MetOptionSet options) {
        this.grpc.readTrailers(fields, options);
    }

    private HttpResponse httpError(Throwable failure, ResponsePayload response) {
        HttpResponse grpc = this.grpc.error(failure, response);
        int code = Integer.parseInt(grpc.trailers().get("grpc-status").get(0));
        int http = switch (code) {
            case 3 -> 400;
            case 4 -> 408;
            case 7 -> 403;
            case 8 -> 413;
            case 12 -> 404;
            case 14 -> 503;
            case 16 -> 401;
            default -> 500;
        };
        if (failure instanceof TripleHttpStatusException rejection) {
            http = rejection.getHttpStatus();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>(grpc.headers());
        headers.put("content-type", List.of("application/json"));
        if (http == 405) {
            headers.put("allow", List.of("POST"));
        }
        GrpcStatusException status = GrpcStatusException.find(failure);
        String message = status == null ? failure.getMessage() : status.getDescription();
        if (message == null) {
            message = "RPC failed";
        }
        try {
            byte[] body = JSON.writeValueAsBytes(Map.of("status", code, "message", message));
            return HttpResponse.newBuffered(http, headers, body, Map.of());
        } catch (IOException unexpected) {
            throw new IllegalStateException("Cannot encode Triple HTTP error", unexpected);
        }
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        String version = request.getServiceVersion();
        MetBindInfo<?> service = this.context.getServiceInfo(group, name, version);
        if (!this.routes.accepts(service)) {
            throw status(12, "Service is not exposed on this protocol endpoint");
        }
        return service;
    }

    private MetBindInfo<?> findService(String alias, HttpRequest input, RouteMount mount) {
        String group = input.header("tri-service-group");
        String version = input.header("tri-service-version");
        MetBindInfo<?> selected = null;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
                continue;
            }
            if (!StringUtils.equals(alias, service.getAliasName(this.config.name()))) {
                continue;
            }
            if (group != null && !StringUtils.equals(group, service.getBindGroup())) {
                continue;
            }
            if (version != null && !StringUtils.equals(version, service.getBindVersion())) {
                continue;
            }
            if (!this.routes.accepts(service, mount)) {
                continue;
            }
            if (selected != null) {
                throw status(3, "Ambiguous Triple service; specify tri-service-group and tri-service-version");
            }
            selected = service;
        }
        if (selected == null) {
            throw status(12, "Unknown Triple service: " + alias);
        }
        return selected;
    }

    private Method remoteMethod(MetBindInfo<?> service, String name) {
        GrpcServiceBinding binding = this.definition(service);
        if (binding != null) {
            return binding.remoteMethod(name);
        }
        String lower = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        Method fallback = null;
        for (Map.Entry<Method, String> entry : this.legacyNames(service).entrySet()) {
            if (StringUtils.equals(entry.getValue(), name)) {
                return entry.getKey();
            }
            boolean original = StringUtils.equals(entry.getValue(), entry.getKey().getName());
            if (original && StringUtils.equals(entry.getValue(), lower)) {
                fallback = entry.getKey();
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw status(12, "Unknown Triple method: " + name);
    }

    @SuppressWarnings("unchecked")
    private Map<Method, String> legacyNames(MetBindInfo<?> service) {
        return (Map<Method, String>) this.routes.binding(service).getDefinition();
    }

    private Method requestMethod(RequestPayload request, List<String> types) {
        MetBindInfo<?> service = this.service(request);
        String remote = request.getOption("triple.remote.method");
        if (remote == null || this.definition(service) != null) {
            return this.findMethod(service, request.getTargetMethod(), types);
        }
        Method selected = null;
        for (Map.Entry<Method, String> entry : this.legacyNames(service).entrySet()) {
            String name = entry.getValue();
            String lower = Character.toLowerCase(remote.charAt(0)) + remote.substring(1);
            boolean original = StringUtils.equals(name, entry.getKey().getName());
            boolean matchesName = StringUtils.equals(remote, name) || original && StringUtils.equals(lower, name);
            if (!matchesName || !matches(service, entry.getKey(), types)) {
                continue;
            }
            if (selected != null) {
                throw status(3, "Ambiguous Triple method: " + remote);
            }
            selected = entry.getKey();
        }
        if (selected == null) {
            throw status(12, "Unknown Triple method: " + remote);
        }
        request.setTargetMethod(selected.getName());
        return selected;
    }

    private Method findMethod(MetBindInfo<?> service, String name, List<String> types) {
        Method[] methods = MetRuntimeUtils.getServiceMethods(service.getBindType());
        boolean exactName = false;
        for (Method method : methods) {
            exactName |= StringUtils.equals(name, method.getName());
        }
        String selectedName = exactName ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
        Method selected = null;
        for (Method method : methods) {
            if (!StringUtils.equals(selectedName, method.getName())) {
                continue;
            }
            if (types != null && !matches(service, method, types)) {
                continue;
            }
            if (selected != null && types != null) {
                throw status(3, "Ambiguous Triple method: " + name);
            }
            selected = method;
        }
        if (selected == null) {
            throw status(12, "Unknown Triple method: " + name);
        }
        return selected;
    }

    private static boolean matches(MetBindInfo<?> service, Method method, List<String> types) {
        if (method.getParameterCount() != types.size()) {
            return false;
        }
        for (int index = 0; index < types.size(); index++) {
            String declared = method.getParameterTypes()[index].getName();
            if (method.getParameterTypes()[index] == Flow.Publisher.class) {
                declared = shape(service, method).requestType().getName();
            }
            String actual = types.get(index);
            Class<?> type = method.getParameterTypes()[index];
            boolean messageType = StringUtils.equals(declared, actual);
            boolean javaType = StringUtils.equals(type.getName(), actual);
            boolean kernelType = StringUtils.equals(MetRuntimeUtils.toAsmType(type), actual);
            if (!messageType && !javaType && !kernelType) {
                return false;
            }
        }
        return true;
    }

    private Method jsonMethod(RequestPayload request, JsonNode input) throws IOException {
        Method selected = null;
        for (Method method : MetRuntimeUtils.getServiceMethods(this.service(request).getBindType())) {
            if (!StringUtils.equals(method.getName(), request.getTargetMethod())) {
                continue;
            }
            int count = input != null && input.isArray() ? input.size() : 1;
            if (count != method.getParameterCount()) {
                continue;
            }
            try {
                this.jsonArguments(input, method);
            } catch (IOException invalid) {
                continue;
            }
            if (selected != null) {
                throw status(3, "Ambiguous Triple JSON method: " + request.getTargetMethod());
            }
            selected = method;
        }
        if (selected == null) {
            throw status(3, "Triple JSON parameters do not match the published method");
        }
        return selected;
    }

    private Object[] jsonArguments(JsonNode input, Method method) throws IOException {
        boolean array = input != null && input.isArray();
        int count = array ? input.size() : 1;
        if (count != method.getParameterCount()) {
            throw status(3, "Triple JSON parameter count differs from the published method");
        }
        Object[] arguments = new Object[count];
        for (int index = 0; index < count; index++) {
            JsonNode value = array ? input.get(index) : input;
            arguments[index] = JSON.treeToValue(value, method.getParameterTypes()[index]);
        }
        return arguments;
    }

    private byte[] nativeEncode(RequestPayload request, Object value, boolean parameter) throws Exception {
        GrpcServiceBinding binding = this.definition(this.service(request));
        DynamicMessage message;
        try {
            message = binding.encode(this.target(request), value, parameter);
        } catch (IllegalArgumentException invalid) {
            throw status(3, invalid.getMessage());
        }
        byte[] bytes;
        if (StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf")) {
            bytes = message.toByteArray();
        } else {
            bytes = binding.json(message).getBytes(StandardCharsets.UTF_8);
        }
        this.frames.check(bytes);
        return bytes;
    }

    private Object nativeDecode(RequestPayload request, Class<?> type, byte[] bytes, //
            boolean parameter) throws Exception {
        this.frames.check(bytes);
        GrpcServiceBinding binding = this.definition(this.service(request));
        Method method = this.target(request);
        if (!StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf")) {
            JsonNode document = JSON.readTree(bytes);
            if (document == null || document.isMissingNode()) {
                throw status(13, "Triple JSON message requires a complete document");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            bytes = binding.parseJson(method, json, parameter).toByteArray();
        }
        if (type == DynamicMessage.class || type == Message.class) {
            return binding.parse(method, bytes, parameter);
        }
        return binding.decode(method, bytes, parameter);
    }

    private GrpcServiceBinding definition(MetBindInfo<?> service) {
        ProtocolBinding binding = this.routes.binding(service);
        if (binding != null && binding.getDefinition() instanceof GrpcServiceBinding definition) {
            return definition;
        }
        return null;
    }

    private String alias(MetBindInfo<?> service) {
        return service.getAliasName(this.config.name());
    }

    private String path(RequestPayload request) {
        MetBindInfo<?> service = this.service(request);
        Method method = this.target(request);
        GrpcServiceBinding definition = this.definition(service);
        String name = definition == null ? this.legacyNames(service).get(method) : definition.remoteName(method);
        return this.routes.requestPath(service, this.alias(service) + "/" + name);
    }

    private String requestMode(RequestPayload request) {
        String selected = request.getOption("grpc-triple-http-mode");
        return selected == null ? this.mode : selected;
    }

    private int httpVersion() {
        return this.config.integer("httpVersion", StringUtils.equals(this.mode, "grpc") ? 2 : 1);
    }

    private String plainMode(HttpRequest request) {
        String type = media(request.header("content-type"));
        if (StringUtils.equals(type, "application/json")) {
            return "json";
        }
        if (StringUtils.equals(type, "application/proto")) {
            return "proto";
        }
        throw new TripleHttpStatusException(415, "Unsupported Triple content-type: " + type);
    }

    private String coding(HttpRequest request, MetBindInfo<?> service) {
        String type = media(request.header("content-type"));
        if (!this.framed(request)) {
            String mode = this.plainMode(request);
            if (StringUtils.equals(mode, "proto") && this.definition(service) == null) {
                throw status(12, "Protobuf service must bind proto file.");
            }
            return StringUtils.equals(mode, "proto") ? "Protobuf" : "Json";
        }
        if (this.definition(service) == null) {
            if (!StringUtils.equals(type, "application/grpc+proto") //
                    && !StringUtils.equals(type, "application/grpc") //
                    && !StringUtils.equals(type, "application/triple+wrapper")) {
                throw status(12, "Triple POJO requires its protobuf wrapper");
            }
            return "Hessian2";
        }
        if (StringUtils.equals(type, "application/grpc+json")) {
            return "Json";
        }
        // 此分支已经取得 proto 定义，SDK 的通用 +data 正文按该定义恢复原生 Protobuf。
        boolean protobuf = StringUtils.equals(type, "application/grpc+proto") //
                || StringUtils.equals(type, "application/grpc") //
                || StringUtils.equals(type, "application/grpc+data");
        if (protobuf) {
            return "Protobuf";
        }
        throw new TripleHttpStatusException(415, "Unsupported Triple content-type: " + type);
    }

    private String contentType(RequestPayload request) {
        if (!this.framed(request)) {
            return StringUtils.equals(this.requestMode(request), "proto") ? "application/proto" : "application/json";
        }
        return this.wrapped(request) || StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf") //
                ? "application/grpc+proto" : "application/grpc+json";
    }

    private Map<String, List<String>> requestHeaders(RequestPayload request) {
        Map<String, List<String>> fields = this.metadata(request);
        fields.putAll(GrpcMetadata.from(request).getHeaders());
        fields.put("content-type", List.of(this.contentType(request)));
        if (this.framed(request)) {
            fields.put("te", List.of("trailers"));
            fields.put("grpc-timeout", List.of(Math.max(1, request.getClientTimeout()) + "m"));
            fields.put("grpc-encoding", List.of(this.compression));
            fields.put("grpc-accept-encoding", List.of("identity,gzip"));
        } else {
            fields.put("tri-protocol-version", List.of("1"));
            fields.put("tri-service-timeout", List.of(String.valueOf(Math.max(1, request.getClientTimeout()))));
            fields.put("content-encoding", List.of(this.compression));
            fields.put("accept-encoding", List.of("identity,gzip"));
        }
        if (StringUtils.isNotBlank(request.getServiceGroup())) {
            fields.put("tri-service-group", List.of(request.getServiceGroup()));
        }
        if (StringUtils.isNotBlank(request.getServiceVersion())) {
            fields.put("tri-service-version", List.of(request.getServiceVersion()));
        }
        return fields;
    }

    private Map<String, List<String>> metadata(MetOptionSet options) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String name = key.toLowerCase(Locale.ROOT);
            String value = options.getOption(key);
            boolean reserved = RESERVED.contains(name) || name.startsWith("grpc-") || name.startsWith("triple-");
            if (value != null && name.matches("[0-9a-z_.-]+") && !reserved) {
                fields.put(name, List.of(value));
            }
        }
        return fields;
    }

    private RouteMount mount(HttpRequest request) {
        RouteSelection selected = request.route();
        if (selected != null) {
            if (!StringUtils.equals(selected.protocolName(), this.config.name())) {
                throw status(3, "Triple request belongs to another mount");
            }
            return selected.mount();
        }
        return this.routes.match(request.uri());
    }

    private int timeout(HttpRequest input, boolean framed) {
        String text = input.header(framed ? "grpc-timeout" : "tri-service-timeout");
        if (text == null) {
            return this.context.getSettings().getDefaultTimeout();
        }
        try {
            if (!framed) {
                if (!text.matches("[0-9]+")) {
                    throw status(3, "Invalid tri-service-timeout");
                }
                return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Long.parseLong(text)));
            }
            if (!text.matches("[0-9]{1,8}[HMSmun]")) {
                throw status(3, "Invalid grpc-timeout");
            }
            long value = Long.parseLong(text.substring(0, text.length() - 1));
            long millis = switch (text.charAt(text.length() - 1)) {
                case 'H' -> value * 3_600_000;
                case 'M' -> value * 60_000;
                case 'S' -> value * 1_000;
                case 'm' -> value;
                case 'u' -> (value + 999) / 1_000;
                case 'n' -> (value + 999_999) / 1_000_000;
                default -> throw status(3, "Invalid grpc-timeout unit");
            };
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
        } catch (NumberFormatException invalid) {
            throw status(3, "Invalid Triple timeout");
        }
    }

    private static String media(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String encoding(String value) {
        String result = value == null || value.isBlank() ? "identity" : value.toLowerCase(Locale.ROOT);
        if (!StringUtils.equals(result, "identity") && !StringUtils.equals(result, "gzip")) {
            throw status(12, "Unsupported Triple compression: " + value);
        }
        return result;
    }

    private static GrpcStatusException status(int code, String message) {
        return new GrpcStatusException(code, message, null, null);
    }
}
