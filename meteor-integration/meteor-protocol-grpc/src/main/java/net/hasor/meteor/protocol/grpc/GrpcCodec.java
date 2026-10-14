/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.connector.ContextPaths;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpBody;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.serialize.SerializeCoder;

/** 负责 Java 消息与标准 gRPC 报文之间的转换，调用仍交给 Caller/Dispatcher。 */
final class GrpcCodec {
    private static final ObjectMapper   MAPPER       = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final Set<String>    HTTP_HEADERS = Set.of("content-type", "content-length", "host", "connection", "transfer-encoding", "te", "user-agent");
    private final        ProtocolConfig config;
    private final        MetContext     context;
    private final        int            limit;
    private final        String         compression;
    private              long           requestId;

    GrpcCodec(ProtocolConfig config, MetContext context) {
        this.config = config;
        this.context = context;
        this.limit = config.integer("maxMessageSize", 4 * 1024 * 1024);
        this.compression = config.option("compression", "identity").toLowerCase(Locale.ROOT);
        if (!StringUtils.equals(this.compression, "identity") && !StringUtils.equals(this.compression, "gzip")) {
            throw new IllegalArgumentException("Unsupported gRPC compression: " + this.compression);
        }
    }

    RequestPayload receive(HttpRequest input) throws Exception {
        RequestPayload request = this.open(input);
        this.readRequest(request, input);
        return request;
    }

    void readRequest(RequestPayload request, HttpRequest input) throws Exception {
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] message = this.unframe(input.body(), input.header("grpc-encoding"));
        Object argument = this.decode(service, method, request.getSerializeType(), message, true);
        request.addParameter(method.getParameterTypes()[0].getName(), argument);
    }

    RequestPayload open(HttpRequest input) throws Exception {
        if (!StringUtils.equals("POST", input.method())) {
            throw new IllegalArgumentException("gRPC requires POST");
        }
        String path = input.uri();
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("gRPC path must not contain a query or fragment");
        }
        ContextPaths.Mount mount = this.mount(input);
        if (mount == null) {
            throw new IllegalArgumentException("gRPC path is outside its mount");
        }
        String prefix = mount.prefix();
        if (path.length() <= prefix.length()) {
            throw new IllegalArgumentException("Expected /service/method");
        }
        String[] parts = path.substring(prefix.length()).split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected /service/method");
        }
        MetBindInfo<?> service = this.context.getServiceInfo("grpc", parts[0]);
        if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
            throw new UnsupportedOperationException("Unknown gRPC service: " + parts[0]);
        }
        if (!this.config.accepts(service, mount)) {
            throw new UnsupportedOperationException("Service is not exposed on this protocol mount");
        }
        Method method = this.findMethod(service, parts[1]);
        String defaultCoding = service.getSerializeType();
        if (StringUtils.isBlank(defaultCoding)) {
            defaultCoding = this.config.option("serializeType", "Protobuf");
        }

        String coding = this.coding(input.header("content-type"), defaultCoding);
        if (!StringUtils.equalsIgnoreCase(coding, defaultCoding)) {
            throw new UnsupportedOperationException("Service expects " + defaultCoding + ", received " + coding);
        }

        RequestPayload request = new RequestPayload();
        request.setRequestID(++this.requestId);
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType(coding);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.timeout(input.header("grpc-timeout")));
        this.readMetadata(input.headers(), request);
        GrpcMetadata.from(request).receive(input.headers(), false);
        request.addOption("grpc-accept-encoding", input.header("grpc-accept-encoding"));
        return request;
    }

    private ContextPaths.Mount mount(HttpRequest input) {
        ContextRoutes.Route route = input.route();
        if (route != null) {
            if (!StringUtils.equals(route.protocol(), this.config.scheme())) {
                throw new IllegalArgumentException("gRPC request belongs to another protocol mount");
            }
            return route.mount();
        }

        // 直接构造的请求没有传输路由，先按标准完整地址检查该服务的精确声明。
        String path = input.uri();
        int separator = path.indexOf('/', 1);
        if (path.startsWith("/") && separator > 1) {
            MetBindInfo<?> service = this.context.getServiceInfo("grpc", path.substring(1, separator));
            if (service != null && this.config.exactPaths(service).contains(path)) {
                return ContextPaths.exact(path);
            }
        }
        return this.config.matchContext(path);
    }

    HttpRequest encode(RequestPayload request) throws Exception {
        if (request.isMessage()) {
            throw new IllegalArgumentException("gRPC unary calls require a response");
        }

        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        if (request.getParameterValues().size() != 1) {
            throw new IllegalArgumentException("gRPC requires one request DTO");
        }

        String coding = request.getSerializeType();
        byte[] body = this.encode(service, method, coding, request.getParameterValues().get(0), true);
        String path = this.config.requestPath(service, this.alias(service) + "/" + method.getName());
        Map<String, List<String>> headers = this.metadata(request);
        headers.putAll(GrpcMetadata.from(request).getHeaders());
        headers.put("content-type", List.of(this.contentType(coding)));
        headers.put("te", List.of("trailers"));
        headers.put("grpc-timeout", List.of(this.timeoutHeader(request.getClientTimeout())));
        headers.put("grpc-accept-encoding", List.of("identity,gzip"));
        headers.put("grpc-encoding", List.of(this.compression));
        return HttpRequest.newBuffered("POST", path, headers, this.frame(body, this.compression), 2, Map.of());
    }

    HttpRequest streamRequest(RequestPayload request, HttpBody body) {
        MetBindInfo<?> service = this.service(request);
        String path = this.config.requestPath(service, this.alias(service) + "/" + request.getTargetMethod());
        Map<String, List<String>> headers = this.metadata(request);
        headers.putAll(GrpcMetadata.from(request).getHeaders());
        headers.put("content-type", List.of(this.contentType(request.getSerializeType())));
        headers.put("grpc-accept-encoding", List.of("identity,gzip"));
        headers.put("grpc-encoding", List.of(this.compression));
        headers.put("te", List.of("trailers"));
        headers.put("grpc-timeout", List.of(this.timeoutHeader(request.getClientTimeout())));
        return HttpRequest.newStreaming("POST", path, headers, body, 2);
    }

    byte[] encodeValue(RequestPayload request, Object value, boolean parameter) throws Exception {
        MetBindInfo<?> service = this.service(request);
        return this.encode(service, this.target(request), request.getSerializeType(), value, parameter);
    }

    Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, boolean parameter) throws Exception {
        String coding = request.getSerializeType();
        if (!StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            return this.coder(coding).decode(bytes, type);
        }

        MetBindInfo<?> service = this.service(request);
        Descriptor descriptor = this.descriptor(service, this.target(request), parameter);
        DynamicMessage message = DynamicMessage.parseFrom(descriptor, bytes);
        GrpcProtoResource resource = (GrpcProtoResource) service.getBindResource();
        String json = JsonFormat.printer().usingTypeRegistry(resource.types()).print(message);
        return MAPPER.readValue(json, type);
    }

    HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
        if (GrpcStatusException.find(response.getFailure()) != null) {
            return this.error(response.getFailure(), response);
        }
        if (response.getStatus() != ProtocolStatus.OK) {
            return this.error(new MetException(response.getStatus(), response.getOption("message")), response);
        }

        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] body = this.encode(service, method, request.getSerializeType(), response.getReturnData(), false);
        Map<String, List<String>> headers = this.responseHeaders(request, response);
        byte[] framed = this.frame(body, this.responseCompression(request));
        return HttpResponse.newBuffered(200, headers, framed, this.responseTrailers(response));
    }

    Map<String, List<String>> responseHeaders(RequestPayload request, ResponsePayload response) {
        GrpcMetadata metadata = GrpcMetadata.from(response);
        Map<String, List<String>> headers = new LinkedHashMap<>(metadata.getHeaders());
        headers.put("content-type", List.of(this.contentType(request.getSerializeType())));
        headers.put("grpc-accept-encoding", List.of("identity,gzip"));
        headers.put("grpc-encoding", List.of(this.responseCompression(request)));
        return headers;
    }

    Map<String, List<String>> responseTrailers(ResponsePayload response) {
        Map<String, List<String>> trailers = this.metadata(response);
        trailers.putAll(GrpcMetadata.from(response).getTrailers());
        trailers.put("grpc-status", List.of("0"));
        return trailers;
    }

    String requestCompression() {
        return this.compression;
    }

    String responseCompression(RequestPayload request) {
        String accepted = request.getOption("grpc-accept-encoding");
        if (accepted != null && StringUtils.equals(this.compression, "gzip")) {
            for (String value : accepted.split(",")) {
                if (StringUtils.equalsIgnoreCase(value.trim(), "gzip")) {
                    return "gzip";
                }
            }
        }
        return "identity";
    }

    ResponsePayload decode(RequestPayload request, HttpResponse input) throws Exception {
        this.validateResponse(request, input, input.trailers());
        String coding = request.getSerializeType();
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] body = this.unframe(input.body(), input.header("grpc-encoding"));
        Object value = this.decode(service, method, coding, body, false);

        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(coding);
        response.setReturnType(method.getReturnType().getName());
        response.setReturnData(value);
        this.readMetadata(input.headers(), response);
        this.readMetadata(input.trailers(), response);
        GrpcMetadata metadata = GrpcMetadata.from(response);
        metadata.receive(input.headers(), false);
        metadata.receive(input.trailers(), true);
        return response;
    }

    void validateResponse(RequestPayload request, HttpResponse input, Map<String, List<String>> trailers) throws IOException {
        this.validateStatus(input, trailers);
        this.validateResponseHeaders(request, input);
    }

    void validateStatus(HttpResponse input, Map<String, List<String>> trailers) throws IOException {
        String status = first(trailers, "grpc-status");
        if (status == null) {
            status = input.header("grpc-status");
        }

        if (status == null) {
            int fallback = switch (input.status()) {
                case 400 -> 13;
                case 401 -> 16;
                case 403 -> 7;
                case 404 -> 12;
                case 429, 502, 503, 504 -> 14;
                default -> 2;
            };
            throw new GrpcStatusException(fallback, "Missing grpc-status, HTTP " + input.status(), null, null);
        }

        int code;
        try {
            code = Integer.parseInt(status);
        } catch (NumberFormatException invalid) {
            throw new GrpcStatusException(2, "Invalid grpc-status: " + status, null, null);
        }
        if (code < 0 || code > 16) {
            throw new GrpcStatusException(2, "Unknown grpc-status: " + status, null, null);
        }

        String details = first(trailers, "grpc-status-details-bin");
        if (details == null) {
            details = input.header("grpc-status-details-bin");
        }

        if (code == 0) {
            if (details != null) {
                throw new GrpcStatusException(13, "Error details accompany an OK status", null, null);
            }
            return;
        }

        String message = first(trailers, "grpc-message");
        if (message == null) {
            message = input.header("grpc-message");
        }
        if (message != null) {
            // 非法百分号编码不能覆盖原始状态和说明。
            try {
                message = URLDecoder.decode(message.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留对端发送的原文。
            }
        }

        GrpcMetadata metadata = new GrpcMetadata();
        if (input.header("grpc-status") != null) {
            metadata.receive(input.headers(), true);
        } else {
            metadata.receive(input.headers(), false);
            metadata.receive(trailers, true);
        }

        GrpcStatusException failure;
        try {
            byte[] bytes = details == null ? null : Base64.getDecoder().decode(details);
            failure = new GrpcStatusException(code, message, bytes, metadata);
        } catch (IllegalArgumentException invalid) {
            throw new GrpcStatusException(13, invalid.getMessage(), null, metadata);
        }

        if (code == 4) {
            throw new MetTimeoutException(failure);
        }
        throw failure;
    }

    void validateResponseHeaders(RequestPayload request, HttpResponse input) throws IOException {
        if (input.status() != 200) {
            throw new IOException("Invalid gRPC HTTP status: " + input.status());
        }

        String coding = this.coding(input.header("content-type"), request.getSerializeType());
        if (!StringUtils.equalsIgnoreCase(coding, request.getSerializeType())) {
            throw new IOException("gRPC response serializer differs from request: " + coding);
        }
    }

    byte[] readMessage(InputStream input, String encoding) throws IOException {
        int first = input.read();
        if (first == -1) {
            return null;
        }

        byte[] size = input.readNBytes(4);
        if (size.length != 4) {
            throw new IOException("Truncated gRPC message prefix");
        }

        long length = Integer.toUnsignedLong(ByteBuffer.wrap(size).getInt());
        if (length > this.limit) {
            throw new GrpcStatusException(8, "gRPC message exceeds configured maxMessageSize", null, null);
        }

        byte[] bytes = input.readNBytes((int) length);
        if (bytes.length != length) {
            throw new IOException("Truncated gRPC message");
        }

        return this.decompress(bytes, first, encoding);
    }

    HttpResponse error(Throwable failure) {
        GrpcStatusException grpc = GrpcStatusException.find(failure);
        if (grpc != null) {
            HttpResponse response = this.failure(grpc.getCode(), grpc.getDescription());
            Map<String, List<String>> headers = new LinkedHashMap<>(grpc.getMetadata().getHeaders());
            headers.putAll(response.headers());
            Map<String, List<String>> trailers = new LinkedHashMap<>(grpc.getMetadata().getTrailers());
            trailers.putAll(response.trailers());
            if (grpc.getDetails().length > 0) {
                String details = Base64.getEncoder().withoutPadding().encodeToString(grpc.getDetails());
                trailers.put("grpc-status-details-bin", List.of(details));
            }
            return HttpResponse.newBuffered(200, headers, new byte[0], trailers);
        }

        int status = 13;
        if (failure instanceof CancellationException) {
            status = 1;
        } else if (failure instanceof UnsupportedOperationException) {
            status = 12;
        } else if (failure instanceof IllegalArgumentException || failure instanceof IOException) {
            status = 3;
        } else if (failure instanceof MetException error) {
            status = this.grpcStatus(error.getStatus());
        }
        return this.failure(status, failure.getMessage());
    }

    HttpResponse error(Throwable failure, ResponsePayload response) {
        HttpResponse error = this.error(failure);
        if (response == null) {
            return error;
        }

        // 业务抛错也保留过滤器已设置的元数据；错误自身的字段和状态优先。
        GrpcMetadata metadata = GrpcMetadata.from(response);
        Map<String, List<String>> headers = new LinkedHashMap<>(metadata.getHeaders());
        headers.putAll(error.headers());
        Map<String, List<String>> trailers = this.metadata(response);
        // Meteor 的错误说明允许 Unicode，只通过百分号编码的 grpc-message 发送。
        trailers.remove("message");
        trailers.putAll(metadata.getTrailers());
        trailers.putAll(error.trailers());
        return HttpResponse.newBuffered(200, headers, new byte[0], trailers);
    }

    private HttpResponse failure(int status, String message) {
        String text = message == null ? "RPC failed" : message;
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, List<String>> trailers = Map.of("grpc-status", List.of(String.valueOf(status)), "grpc-message", List.of(encoded));
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc"), "grpc-accept-encoding", List.of("identity,gzip"));
        return HttpResponse.newBuffered(200, headers, new byte[0], trailers);
    }

    HttpResponse trailersOnly(HttpResponse response) {
        boolean error = response.body().length == 0 && response.trailers().containsKey("grpc-status");
        if (!error) {
            return response;
        }
        // 尚未发送任何业务头部或消息的失败，使用单个 HEADERS 结束流，允许标准客户端重试。
        for (String name : response.headers().keySet()) {
            boolean internal = StringUtils.equalsIgnoreCase(name, "content-type") || StringUtils.equalsIgnoreCase(name, "grpc-accept-encoding");
            if (!internal) {
                return response;
            }
        }
        Map<String, List<String>> headers = new LinkedHashMap<>(response.headers());
        headers.putAll(response.trailers());
        return HttpResponse.newBuffered(response.status(), headers, new byte[0], Map.of());
    }

    private byte[] encode(MetBindInfo<?> service, Method method, String coding, Object value, boolean request) throws Exception {
        if (!StringUtils.equalsIgnoreCase("Protobuf", coding)) {
            return this.coder(coding).encode(value);
        }

        Descriptor type = this.descriptor(service, method, request);
        DynamicMessage.Builder message = DynamicMessage.newBuilder(type);
        GrpcProtoResource resource = (GrpcProtoResource) service.getBindResource();
        JsonFormat.parser().usingTypeRegistry(resource.types()).merge(MAPPER.writeValueAsString(value), message);
        return message.build().toByteArray();
    }

    private Object decode(MetBindInfo<?> service, Method method, String coding, byte[] body, boolean request) throws Exception {
        GrpcMethod signature = new GrpcMethod(method);
        Class<?> type = request ? signature.requestType() : signature.responseType();
        if (!StringUtils.equalsIgnoreCase("Protobuf", coding)) {
            return this.coder(coding).decode(body, type);
        }

        DynamicMessage message = DynamicMessage.parseFrom(this.descriptor(service, method, request), body);
        GrpcProtoResource resource = (GrpcProtoResource) service.getBindResource();
        String json = JsonFormat.printer().usingTypeRegistry(resource.types()).print(message);
        return MAPPER.readValue(json, type);
    }

    private Descriptor descriptor(MetBindInfo<?> service, Method method, boolean request) {
        if (!(service.getBindResource() instanceof GrpcProtoResource proto)) {
            throw new IllegalArgumentException("Protobuf service must bind proto file.");
        }

        MethodDescriptor descriptor = proto.method(this.alias(service), method.getName());
        GrpcMethod signature = new GrpcMethod(method);
        boolean client = descriptor.isClientStreaming() == signature.streamingRequest();
        boolean server = descriptor.isServerStreaming() == signature.streamingResponse();
        if (!client || !server) {
            throw new IllegalArgumentException("Java Flow signature differs from proto RPC: " + method.getName());
        }
        return request ? descriptor.getInputType() : descriptor.getOutputType();
    }

    private Map<String, List<String>> metadata(MetOptionSet options) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String value = options.getOption(key);
            if (key == null || value == null) {
                continue;
            }

            String name = key.toLowerCase(Locale.ROOT);
            if (isMetadata(name)) {
                headers.put(name, List.of(value));
            }
        }
        return headers;
    }

    void readMetadata(Map<String, List<String>> fields, MetOptionSet options) {
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (isMetadata(name)) {
                // 普通选项保持首值兼容；完整字段由 gRPC 的 JSON 选项保存。
                if (!field.getValue().isEmpty()) {
                    options.addOption(name, field.getValue().get(0));
                }
            }
        }
    }

    private static String first(Map<String, List<String>> fields, String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static boolean isMetadata(String name) {
        return name.matches("[0-9a-z_.-]+") && !name.startsWith("grpc-") && !HTTP_HEADERS.contains(name);
    }

    private SerializeCoder coder(String name) {
        SerializeCoder coder = this.context.getSerializeCoder(name);
        if (coder == null) {
            throw new UnsupportedOperationException("Unknown gRPC serializer: " + name);
        }
        return coder;
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        String version = request.getServiceVersion();
        return this.context.getServiceInfo(group, name, version);
    }

    private String alias(MetBindInfo<?> service) {
        String name = service.getAliasName("grpc");
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Service must declare its grpc alias");
        }
        return name;
    }

    Method target(RequestPayload request) {
        MetBindInfo<?> service = this.service(request);
        Method method = this.findMethod(service, request.getTargetMethod());
        if (StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf")) {
            this.descriptor(service, method, true);
        }
        return method;
    }

    private Method findMethod(MetBindInfo<?> service, String name) {
        Method selected = null;
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (StringUtils.equals(name, method.getName())) {
                if (selected != null) {
                    throw new IllegalArgumentException("gRPC method names cannot be overloaded: " + name);
                }
                selected = method;
            }
        }

        if (selected == null) {
            throw new UnsupportedOperationException("Unknown gRPC method: " + name);
        }
        new GrpcMethod(selected);
        return selected;
    }

    private Method method(MetBindInfo<?> service, String name) {
        Method selected = this.findMethod(service, name);
        if (new GrpcMethod(selected).streaming()) {
            throw new IllegalArgumentException("Streaming methods require incremental HTTP bodies");
        }
        return selected;
    }

    private String coding(String contentType, String defaultCoding) {
        if (contentType == null) {
            throw new IllegalArgumentException("Missing gRPC content-type");
        }

        String media = contentType.split(";", 2)[0].trim();
        if (StringUtils.equalsIgnoreCase(media, "application/grpc")) {
            return defaultCoding;
        }
        if (StringUtils.equalsIgnoreCase(media, "application/grpc+proto")) {
            return "Protobuf";
        }

        String prefix = "application/grpc+";
        if (media.toLowerCase(Locale.ROOT).startsWith(prefix) && media.length() > prefix.length()) {
            return media.substring(prefix.length());
        }
        throw new IllegalArgumentException("Unsupported gRPC content-type: " + contentType);
    }

    String contentType(String coding) {
        String suffix = StringUtils.equalsIgnoreCase(coding, "Protobuf") ? "proto" : coding.toLowerCase(Locale.ROOT);
        return "application/grpc+" + suffix;
    }

    byte[] frame(byte[] message) throws IOException {
        return this.frame(message, "identity");
    }

    byte[] frame(byte[] message, String encoding) throws IOException {
        if (message.length > this.limit) {
            throw new GrpcStatusException(8, "gRPC message exceeds configured maxMessageSize", null, null);
        }

        byte flag = 0;
        byte[] bytes = message;
        if (StringUtils.equals(encoding, "gzip")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(message);
            }
            bytes = buffer.toByteArray();
            flag = 1;
        }

        if (bytes.length > this.limit) {
            throw new GrpcStatusException(8, "Compressed gRPC message exceeds configured maxMessageSize", null, null);
        }
        return ByteBuffer.allocate(5 + bytes.length).put(flag).putInt(bytes.length).put(bytes).array();
    }

    private byte[] unframe(byte[] input, String encoding) throws IOException {
        if (input.length < 5) {
            throw new IOException("Truncated gRPC message prefix");
        }

        ByteBuffer frame = ByteBuffer.wrap(input);
        byte compressed = frame.get();
        long length = Integer.toUnsignedLong(frame.getInt());
        if (length > this.limit) {
            throw new GrpcStatusException(8, "gRPC message exceeds configured maxMessageSize", null, null);
        }

        if (length != frame.remaining()) {
            throw new IOException("Expected exactly one gRPC message");
        }

        byte[] message = new byte[(int) length];
        frame.get(message);
        return this.decompress(message, compressed, encoding);
    }

    private byte[] decompress(byte[] message, int compressed, String encoding) throws IOException {
        if (compressed == 0) {
            return message;
        }

        if (compressed != 1) {
            throw new IOException("Invalid gRPC compression flag");
        }

        if (!StringUtils.equalsIgnoreCase("gzip", encoding)) {
            throw new GrpcStatusException(12, "Unsupported gRPC message compression: " + encoding, null, null);
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(message))) {
            byte[] expanded = gzip.readNBytes(this.limit + 1);
            if (expanded.length > this.limit) {
                String messageTooLarge = "Decompressed gRPC message exceeds configured maxMessageSize";
                throw new GrpcStatusException(8, messageTooLarge, null, null);
            }
            return expanded;
        }
    }

    private String timeoutHeader(int millis) {
        if (millis <= 99_999_999) {
            return Math.max(1, millis) + "m";
        } else {
            return ((millis + 999L) / 1000) + "S";
        }

    }

    private int timeout(String value) {
        if (value == null) {
            return this.context.getSettings().getDefaultTimeout();
        }

        if (!value.matches("[0-9]{1,8}[HMSmun]")) {
            throw new IllegalArgumentException("Invalid grpc-timeout");
        }

        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        long millis = switch (value.charAt(value.length() - 1)) {
            case 'H' -> amount * 3_600_000;
            case 'M' -> amount * 60_000;
            case 'S' -> amount * 1_000;
            case 'm' -> amount;
            case 'u' -> (amount + 999) / 1_000;
            case 'n' -> (amount + 999_999) / 1_000_000;
            default -> throw new IllegalArgumentException("Invalid grpc-timeout unit");
        };

        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    private int grpcStatus(short status) {
        return switch (status) {
            case ProtocolStatus.OK -> 0;
            case ProtocolStatus.Timeout -> 4;
            case ProtocolStatus.Forbidden -> 7;
            case ProtocolStatus.Unauthorized -> 16;
            case ProtocolStatus.NotFound -> 12;
            case ProtocolStatus.QueueFull, ProtocolStatus.SendLimitPolicy -> 8;
            case ProtocolStatus.NetworkError -> 14;
            default -> 13;
        };
    }

}
