/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.serialize.SerializeCoder;

/** 负责 Java 消息与标准 gRPC 报文之间的转换，调用仍交给 Caller/Dispatcher。 */
public final class GrpcHttpCodec {
    private static final ObjectMapper                                    MAPPER         = new ObjectMapper()//
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final Set<String>                                     HTTP_HEADERS   = Set.of(//
            "content-type", "content-length", "host", "connection", "transfer-encoding", "te", "user-agent");
    private final        ProtocolConfig                                  config;
    private final        MetContext                                      context;
    private final        GrpcFrameCodec                                  frames;
    private final        String                                          compression;
    private final        Map<GrpcProtoResource, JsonFormat.TypeRegistry> typeRegistries = new WeakHashMap<>();

    public GrpcHttpCodec(ProtocolConfig config, MetContext context) {
        this.config = config;
        this.context = context;
        this.frames = new GrpcFrameCodec(config.integer("maxMessageSize", 4 * 1024 * 1024));
        this.compression = config.option("compression", "identity").toLowerCase(Locale.ROOT);
        if (!StringUtils.equals(this.compression, "identity") && !StringUtils.equals(this.compression, "gzip")) {
            throw new IllegalArgumentException("Unsupported gRPC compression: " + this.compression);
        }
    }

    public void readRequest(RequestPayload request, HttpRequest input) throws Exception {
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] message = this.frames.unframe(input.body(), input.header("grpc-encoding"));
        Object argument = this.decode(service, method, request.getSerializeType(), message, true);
        request.addParameter(method.getParameterTypes()[0].getName(), argument);
    }

    public RequestPayload open(HttpRequest input) throws Exception {
        if (!StringUtils.equals("POST", input.method())) {
            throw new IllegalArgumentException("gRPC requires POST");
        }
        String path = input.uri();
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("gRPC path must not contain a query or fragment");
        }
        RouteMount mount = this.mount(input);
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
        MetBindInfo<?> service = this.context.getServiceInfo(this.config.name(), parts[0]);
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
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType(coding);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.timeout(input.header("grpc-timeout")));
        this.readHeaders(input.headers(), request);
        request.addOption("grpc-accept-encoding", input.header("grpc-accept-encoding"));
        return request;
    }

    private RouteMount mount(HttpRequest input) {
        RouteSelection route = input.route();
        if (route != null) {
            if (!StringUtils.equals(route.mountId(), this.config.mountId())) {
                throw new IllegalArgumentException("gRPC request belongs to another protocol mount");
            }
            return route.mount();
        }

        // 直接构造的请求没有传输路由，先按标准完整地址检查该服务的精确声明。
        String path = input.uri();
        int separator = path.indexOf('/', 1);
        if (path.startsWith("/") && separator > 1) {
            MetBindInfo<?> service = this.context.getServiceInfo(this.config.name(), path.substring(1, separator));
            if (service != null && this.config.exactPaths(service).contains(path)) {
                return RouteMount.exact(path);
            }
        }
        return this.config.matchContext(path);
    }

    public HttpRequest encode(RequestPayload request) throws Exception {
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

    public HttpRequest streamRequest(RequestPayload request, HttpBodyStream body) {
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

    public byte[] encodeValue(RequestPayload request, Object value, boolean parameter) throws Exception {
        MetBindInfo<?> service = this.service(request);
        return this.encode(service, this.target(request), request.getSerializeType(), value, parameter);
    }

    public Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, boolean parameter) throws Exception {
        String coding = request.getSerializeType();
        if (!StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            return this.coder(coding).decode(bytes, type);
        }

        MetBindInfo<?> service = this.service(request);
        Descriptor descriptor = this.descriptor(service, this.target(request), parameter);
        DynamicMessage message = DynamicMessage.parseFrom(descriptor, bytes);
        JsonFormat.TypeRegistry registry = this.typeRegistry(service);
        String json = JsonFormat.printer().usingTypeRegistry(registry).print(message);
        return MAPPER.readValue(json, type);
    }

    public HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
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

    public Map<String, List<String>> responseHeaders(RequestPayload request, ResponsePayload response) {
        GrpcMetadata metadata = GrpcMetadata.from(response);
        Map<String, List<String>> headers = new LinkedHashMap<>(metadata.getHeaders());
        headers.put("content-type", List.of(this.contentType(request.getSerializeType())));
        headers.put("grpc-accept-encoding", List.of("identity,gzip"));
        headers.put("grpc-encoding", List.of(this.responseCompression(request)));
        return headers;
    }

    public Map<String, List<String>> responseTrailers(ResponsePayload response) {
        Map<String, List<String>> trailers = this.metadata(response);
        trailers.putAll(GrpcMetadata.from(response).getTrailers());
        trailers.put("grpc-status", List.of("0"));
        return trailers;
    }

    public String requestCompression() {
        return this.compression;
    }

    public String responseCompression(RequestPayload request) {
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

    public ResponsePayload decode(RequestPayload request, HttpResponse input) throws Exception {
        this.validateResponse(request, input, input.trailers());
        String coding = request.getSerializeType();
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] body = this.frames.unframe(input.body(), input.header("grpc-encoding"));
        Object value = this.decode(service, method, coding, body, false);

        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(coding);
        response.setReturnType(method.getReturnType().getName());
        response.setReturnData(value);
        this.readHeaders(input.headers(), response);
        this.readTrailers(input.trailers(), response);
        return response;
    }

    void validateResponse(RequestPayload request, HttpResponse input, Map<String, List<String>> trailers) throws IOException {
        this.validateStatus(input, trailers);
        this.validateResponseHeaders(request, input);
    }

    public void validateStatus(HttpResponse input, Map<String, List<String>> trailers) throws IOException {
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

        GrpcMetadata metadata = GrpcMetadata.create();
        if (input.header("grpc-status") != null) {
            receiveTrailers(metadata, input.headers());
        } else {
            receiveHeaders(metadata, input.headers());
            receiveTrailers(metadata, trailers);
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

    public void validateResponseHeaders(RequestPayload request, HttpResponse input) throws IOException {
        if (input.status() != 200) {
            throw new IOException("Invalid gRPC HTTP status: " + input.status());
        }

        String coding = this.coding(input.header("content-type"), request.getSerializeType());
        if (!StringUtils.equalsIgnoreCase(coding, request.getSerializeType())) {
            throw new IOException("gRPC response serializer differs from request: " + coding);
        }
    }

    public byte[] readMessage(InputStream input, String encoding) throws IOException {
        return this.frames.readMessage(input, encoding);
    }

    public HttpResponse error(Throwable failure) {
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

    public HttpResponse error(Throwable failure, ResponsePayload response) {
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
        Map<String, List<String>> trailers = Map.of(//
                "grpc-status", List.of(String.valueOf(status)), "grpc-message", List.of(encoded));
        Map<String, List<String>> headers = Map.of(//
                "content-type", List.of("application/grpc"), //
                "grpc-accept-encoding", List.of("identity,gzip"));
        return HttpResponse.newBuffered(200, headers, new byte[0], trailers);
    }

    public HttpResponse trailersOnly(HttpResponse response) {
        boolean error = response.body().length == 0 && response.trailers().containsKey("grpc-status");
        if (!error) {
            return response;
        }
        // 尚未发送任何业务头部或消息的失败，使用单个 HEADERS 结束流，允许标准客户端重试。
        for (String name : response.headers().keySet()) {
            boolean contentType = StringUtils.equalsIgnoreCase(name, "content-type");
            boolean acceptEncoding = StringUtils.equalsIgnoreCase(name, "grpc-accept-encoding");
            boolean internal = contentType || acceptEncoding;
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
        JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(this.typeRegistry(service));
        parser.merge(MAPPER.writeValueAsString(value), message);
        return message.build().toByteArray();
    }

    private Object decode(MetBindInfo<?> service, Method method, String coding, byte[] body, boolean request) throws Exception {
        GrpcMethodShape signature = new GrpcMethodShape(method);
        Class<?> type = request ? signature.requestType() : signature.responseType();
        if (!StringUtils.equalsIgnoreCase("Protobuf", coding)) {
            return this.coder(coding).decode(body, type);
        }

        Descriptor descriptor = this.descriptor(service, method, request);
        DynamicMessage message = DynamicMessage.parseFrom(descriptor, body);
        String json = JsonFormat.printer().usingTypeRegistry(this.typeRegistry(service)).print(message);
        return MAPPER.readValue(json, type);
    }

    private Descriptor descriptor(MetBindInfo<?> service, Method method, boolean request) {
        GrpcProtoResource proto = this.resource(service);
        MethodDescriptor descriptor = proto.method(this.alias(service), method.getName());
        GrpcMethodShape signature = new GrpcMethodShape(method);
        boolean client = descriptor.isClientStreaming() == signature.streamingRequest();
        boolean server = descriptor.isServerStreaming() == signature.streamingResponse();
        if (!client || !server) {
            throw new IllegalArgumentException("Java Flow signature differs from proto RPC: " + method.getName());
        }
        return request ? descriptor.getInputType() : descriptor.getOutputType();
    }

    private JsonFormat.TypeRegistry typeRegistry(MetBindInfo<?> service) {
        GrpcProtoResource resource = this.resource(service);
        synchronized (this.typeRegistries) {
            return this.typeRegistries.computeIfAbsent(resource, this::createTypeRegistry);
        }
    }

    private JsonFormat.TypeRegistry createTypeRegistry(GrpcProtoResource resource) {
        JsonFormat.TypeRegistry.Builder registry = JsonFormat.TypeRegistry.newBuilder();
        Set<String> visited = new HashSet<>();
        for (FileDescriptor descriptor : resource.fileDescriptors()) {
            this.addTypes(descriptor, registry, visited);
        }
        return registry.build();
    }

    private GrpcProtoResource resource(MetBindInfo<?> service) {
        if (service.getBindResource() instanceof GrpcProtoResource resource) {
            return resource;
        }
        throw new IllegalArgumentException("Protobuf service must bind proto file.");
    }

    private void addTypes(FileDescriptor file, JsonFormat.TypeRegistry.Builder registry, Set<String> visited) {
        if (!visited.add(file.getFullName())) {
            return;
        }

        for (FileDescriptor dependency : file.getDependencies()) {
            this.addTypes(dependency, registry, visited);
        }
        registry.add(file.getMessageTypes());
    }

    private Map<String, List<String>> metadata(MetOptionSet options) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String value = options.getOption(key);
            if (key == null || value == null) {
                continue;
            }

            String name = key.toLowerCase(Locale.ROOT);
            if (isApplicationKey(name)) {
                headers.put(name, List.of(value));
            }
        }
        return headers;
    }

    public void readHeaders(Map<String, List<String>> fields, MetOptionSet options) {
        this.readMetadata(fields, options);
        receiveHeaders(GrpcMetadata.from(options), fields);
    }

    public void readTrailers(Map<String, List<String>> fields, MetOptionSet options) {
        this.readMetadata(fields, options);
        receiveTrailers(GrpcMetadata.from(options), fields);
    }

    private void readMetadata(Map<String, List<String>> fields, MetOptionSet options) {
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (isApplicationKey(name)) {
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

    private static void receiveHeaders(GrpcMetadata metadata, Map<String, List<String>> fields) {
        receiveMetadata(metadata, fields, false);
    }

    private static void receiveTrailers(GrpcMetadata metadata, Map<String, List<String>> fields) {
        receiveMetadata(metadata, fields, true);
    }

    private static void receiveMetadata(GrpcMetadata metadata, Map<String, List<String>> fields, boolean trailers) {
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (!isApplicationKey(name)) {
                continue;
            }

            for (String value : field.getValue()) {
                if (name.endsWith("-bin")) {
                    receiveBinaryMetadata(metadata, name, value, trailers);
                } else if (trailers) {
                    metadata.addTrailer(name, value);
                } else {
                    metadata.addHeader(name, value);
                }
            }
        }
    }

    private static void receiveBinaryMetadata(GrpcMetadata metadata, String name, String value, boolean trailers) {
        // HTTP 可以合并同名二进制字段，逐项恢复后交给元数据 API 重新编码。
        for (String part : value.split(",", -1)) {
            byte[] bytes = Base64.getDecoder().decode(part.trim());
            if (trailers) {
                metadata.addBinaryTrailer(name, bytes);
            } else {
                metadata.addBinaryHeader(name, bytes);
            }
        }
    }

    private static boolean isApplicationKey(String name) {
        return name != null && name.matches("[0-9a-z_.-]+") && !name.startsWith("grpc-") && //
                !HTTP_HEADERS.contains(name);
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
        String name = service.getAliasName(this.config.name());
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Service must declare its " + this.config.name() + " alias");
        }
        return name;
    }

    public Method target(RequestPayload request) {
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
        new GrpcMethodShape(selected);
        return selected;
    }

    private Method method(MetBindInfo<?> service, String name) {
        Method selected = this.findMethod(service, name);
        if (new GrpcMethodShape(selected).streaming()) {
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

    public byte[] frame(byte[] message, String encoding) throws IOException {
        return this.frames.frame(message, encoding);
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
