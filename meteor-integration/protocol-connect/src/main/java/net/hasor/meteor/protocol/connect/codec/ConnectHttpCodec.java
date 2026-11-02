/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel;
import com.google.protobuf.Descriptors.MethodDescriptor;
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
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.connect.ConnectMetadata;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.binding.GrpcMethodShape;
import net.hasor.meteor.protocol.grpc.binding.GrpcServiceBinding;

/** Connect HTTP 信封及 Java/Protobuf 消息映射；不承担调用调度或连接生命周期。 */
public final class ConnectHttpCodec {
    private static final ObjectMapper      MAPPER;
    private static final Set<String>       HTTP_FIELDS;
    private final        ProtocolConfig    config;
    private final        RouteMountIndex   routes;
    private final        MetContext        context;
    private final        ConnectFrameCodec frames;
    private final        String            compression;

    static {
        MAPPER = JsonMapper.builder() //
                .serializationInclusion(JsonInclude.Include.NON_NULL)   //
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)  //
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //
                .build();
        HTTP_FIELDS = Set.of("content-type", "content-length", "host",  //
                "message", "content-encoding", "accept-encoding", "te", //
                "connection", "transfer-encoding", "user-agent");
    }

    public ConnectHttpCodec(ProtocolConfig config, RouteMountIndex routes, MetContext context) {
        this.config = config;
        this.routes = routes;
        this.context = context;
        this.frames = new ConnectFrameCodec(config.integer("maxMessageSize", 4 * 1024 * 1024));
        this.compression = config.option("compression", "identity").toLowerCase(Locale.ROOT);
        if (!StringUtils.equals(this.compression, "identity") && !StringUtils.equals(this.compression, "gzip")) {
            throw new IllegalArgumentException("Unsupported Connect compression: " + this.compression);
        }
        int version = config.integer("httpVersion", 1);
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("Connect httpVersion must be 1 or 2");
        }
    }

    /** 发布时校验消息签名和已绑定的 proto；普通 Java JSON 服务不要求 proto。 */
    public static GrpcServiceBinding prepareServiceDefinition(ProtocolConfig config, ProtocolMeta meta) {
        String coding = meta.getService().getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", "Protobuf");
        }
        if (!StringUtils.equalsIgnoreCase(coding, "Json") && !StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            return null;
        }
        return GrpcServiceBinding.prepare(config, meta, "Protobuf");
    }

    public RequestPayload open(HttpRequest input) throws Exception {
        boolean get = StringUtils.equals(input.method(), "GET");
        if (!get && !StringUtils.equals(input.method(), "POST")) {
            throw new ConnectHttpStatusException(405, "Connect requires POST or a side-effect-free GET");
        }
        String version = input.header("connect-protocol-version");
        if (version != null && !StringUtils.equals(version, "1")) {
            throw invalid("Unsupported Connect protocol version");
        }
        String path = path(input.uri());
        RouteMount mount = this.mount(input, path);
        if (mount == null || path.length() <= mount.prefix().length()) {
            throw invalid("Expected /service/method within the Connect mount");
        }
        String[] parts = path.substring(mount.prefix().length()).split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalid("Expected /service/method");
        }
        MetBindInfo<?> service = this.context.getServiceInfo(this.config.name(), parts[0]);
        if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
            throw new UnsupportedOperationException("Unknown Connect service: " + parts[0]);
        }
        if (!this.routes.accepts(service, mount)) {
            throw new UnsupportedOperationException("Service is not exposed on this protocol mount");
        }
        Method method = this.definition(service).remoteMethod(parts[1]);
        GrpcMethodShape shape = this.definition(service).shape(method);
        Map<String, String> query = get ? query(input.uri()) : Map.of();
        String coding = get ? this.queryCoding(query) : this.coding(input.header("content-type"), shape.streaming());
        if (get) {
            this.validateGet(service, method, input, query);
        }
        if (!get && input.uri().indexOf('?') >= 0) {
            throw invalid("Connect POST path must not contain a query");
        }
        if (shape.streamingRequest() && shape.streamingResponse() && input.version() != 2) {
            throw new UnsupportedOperationException("Bidirectional Connect streams require HTTP/2");
        }
        if (StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            this.definition(service).descriptor(method);
        }
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType(coding);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.timeout(input.header("connect-timeout-ms")));
        this.readHeaders(input.headers(), request);
        String encoding = get ? query.get("compression") : input.header(shape.streaming() //
                ? "connect-content-encoding" : "content-encoding");
        request.addOption("connect-content-encoding", normalizedEncoding(encoding));
        String accept = input.header(shape.streaming() ? "connect-accept-encoding" : "accept-encoding");
        request.addOption("connect-accept-encoding", accept == null ? normalizedEncoding(encoding) : accept);
        return request;
    }

    public Method target(RequestPayload request) {
        return findMethod(this.service(request), request.getTargetMethod());
    }

    public GrpcMethodShape shape(RequestPayload request) {
        return this.definition(this.service(request)).shape(this.target(request));
    }

    public void readRequest(RequestPayload request, HttpRequest input) throws Exception {
        Method method = this.target(request);
        GrpcMethodShape shape = this.definition(this.service(request)).shape(method);
        if (shape.streaming()) {
            throw invalid("Streaming methods require incremental HTTP bodies");
        }
        byte[] body;
        String encoding = request.getOption("connect-content-encoding");
        if (StringUtils.equals(input.method(), "GET")) {
            if (input.body().length != 0) {
                throw invalid("Connect GET request body must be empty");
            }
            Map<String, String> query = query(input.uri());
            String message = query.get("message");
            if (StringUtils.equals(query.get("base64"), "1")) {
                try {
                    body = Base64.getUrlDecoder().decode(message);
                } catch (IllegalArgumentException failure) {
                    throw invalid("Invalid Connect GET base64 message");
                }
            } else {
                body = message.getBytes(StandardCharsets.UTF_8);
            }
        } else {
            body = input.body();
        }
        byte[] decoded = this.frames.decompress(body, encoding);
        Object value = this.decodeValue(request, shape.requestType(), decoded, true);
        request.addParameter(method.getParameterTypes()[0].getName(), value);
    }

    public HttpRequest encode(RequestPayload request) throws Exception {
        if (request.isMessage()) {
            throw invalid("Connect requires a response");
        }
        Method method = this.target(request);
        GrpcMethodShape shape = this.definition(this.service(request)).shape(method);
        if (shape.streaming() || request.getParameterValues().size() != 1) {
            throw invalid("Connect unary calls require one request DTO");
        }
        MetBindInfo<?> service = this.service(request);
        String operation = this.definition(service).remoteName(method);
        String path = this.routes.requestPath(service, this.alias(service) + "/" + operation);
        Map<String, List<String>> headers = this.requestHeaders(request, false);
        byte[] body = this.encodeValue(request, request.getParameterValues().get(0), true);
        body = this.frames.compress(body, this.compression);
        if (Boolean.parseBoolean(this.config.option("get", "false"))) {
            this.sideEffectFree(service, method);
            String message = Base64.getUrlEncoder().withoutPadding().encodeToString(body);
            String coding = StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf") ? "proto" : "json";
            path += "?connect=v1&base64=1&compression=" + this.compression //
                    + "&encoding=" + coding + "&message=" + url(message);
            headers.remove("content-type");
            headers.remove("content-encoding");
            return HttpRequest.newBuffered("GET", path, headers, new byte[0], this.httpVersion(), Map.of());
        }
        return HttpRequest.newBuffered("POST", path, headers, body, this.httpVersion(), Map.of());
    }

    public HttpRequest streamRequest(RequestPayload request, HttpBodyStream body) {
        MetBindInfo<?> service = this.service(request);
        Method method = this.target(request);
        GrpcMethodShape shape = this.definition(this.service(request)).shape(method);
        if (shape.streamingRequest() && shape.streamingResponse() && this.httpVersion() != 2) {
            throw new UnsupportedOperationException("Bidirectional Connect streams require HTTP/2");
        }
        String operation = this.definition(service).remoteName(method);
        String path = this.routes.requestPath(service, this.alias(service) + "/" + operation);
        return HttpRequest.newStreaming("POST", path, this.requestHeaders(request, true), body, this.httpVersion());
    }

    public byte[] encodeValue(RequestPayload request, Object value, boolean parameter) throws Exception {
        MetBindInfo<?> service = this.service(request);
        boolean protobuf = StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf");
        GrpcServiceBinding binding = this.definition(service);
        byte[] result;
        if (binding.schema() == null) {
            if (protobuf) {
                throw invalid("Protobuf service has no prepared definition");
            }
            result = MAPPER.writeValueAsBytes(value);
        } else {
            DynamicMessage message;
            try {
                message = binding.encode(this.target(request), value, parameter);
            } catch (IllegalArgumentException invalid) {
                throw invalid(invalid.getMessage());
            }
            if (protobuf) {
                result = new byte[message.getSerializedSize()];
                CodedOutputStream output = CodedOutputStream.newInstance(result);
                output.useDeterministicSerialization();
                message.writeTo(output);
                output.checkNoSpaceLeft();
            } else {
                result = binding.json(message).getBytes(StandardCharsets.UTF_8);
            }
        }
        // 编码和解码使用相同上限，避免调用方绕过收端的消息边界。
        return this.frames.decompress(result, "identity");
    }

    public Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, boolean parameter) throws Exception {
        this.frames.decompress(bytes, "identity");
        MetBindInfo<?> service = this.service(request);
        boolean protobuf = StringUtils.equalsIgnoreCase(request.getSerializeType(), "Protobuf");
        GrpcServiceBinding binding = this.definition(service);
        if (binding.schema() == null) {
            if (protobuf) {
                throw invalid("Protobuf service has no prepared definition");
            }
            return MAPPER.readValue(bytes, type);
        }
        Method method = this.target(request);
        if (!protobuf) {
            String json = utf8(bytes);
            // 先确认只有一个 JSON 文档，再按官方 ProtoJSON 规则解释正文。
            MAPPER.readTree(json);
            bytes = binding.parseJson(method, json, parameter).toByteArray();
        }
        if (type == DynamicMessage.class || type == Message.class) {
            return binding.parse(method, bytes, parameter);
        }
        return binding.decode(method, bytes, parameter);
    }

    public HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
        if (response.getFailure() != null || response.getStatus() != ProtocolStatus.OK) {
            Throwable failure = response.getFailure();
            if (failure == null) {
                failure = new MetException(response.getStatus(), response.getOption("message"));
            }
            return this.error(failure, response);
        }

        Map<String, List<String>> headers = this.responseHeaders(request, response);
        // Connect unary 的尾部字段以 trailer- 前缀放在 HTTP 头中。
        for (Map.Entry<String, List<String>> field : ConnectMetadata.from(response).getTrailers().entrySet()) {
            headers.put("trailer-" + field.getKey(), field.getValue());
        }

        byte[] value = this.encodeValue(request, response.getReturnData(), false);
        byte[] body = this.frames.compress(value, this.responseCompression(request));
        return HttpResponse.newBuffered(200, headers, body, Map.of());
    }

    public ResponsePayload decode(RequestPayload request, HttpResponse input) throws Exception {
        this.validateStatus(input);
        this.validateResponseHeaders(request, input);
        Method method = this.target(request);
        Class<?> type = this.definition(this.service(request)).shape(method).responseType();
        byte[] body = this.frames.decompress(input.body(), input.header("content-encoding"));
        Object value = this.decodeValue(request, type, body, false);
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(request.getSerializeType());
        response.setReturnType(type.getName());
        response.setReturnData(value);
        this.readResponseHeaders(input.headers(), response);
        return response;
    }

    public void validateStatus(HttpResponse input) throws IOException {
        if (input.status() == 200) {
            return;
        }
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> field : input.headers().entrySet()) {
            if (applicationKey(field.getKey().toLowerCase(Locale.ROOT))) {
                metadata.put(field.getKey(), field.getValue());
            }
        }
        try {
            if (!StringUtils.equalsIgnoreCase(media(input.header("content-type")), "application/json")) {
                throw new IOException("Connect HTTP error has no JSON content-type");
            }
            byte[] encoded;
            if (input.stream() == null) {
                encoded = input.body();
            } else {
                int maximum = this.config.integer("maxMessageSize", 4 * 1024 * 1024);
                encoded = input.stream().readNBytes(maximum + 1);
            }
            byte[] body = this.frames.decompress(encoded, input.header("content-encoding"));
            throw this.frames.readError(body, metadata);
        } catch (ConnectStatusException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new ConnectStatusException(ConnectStatusException.fromHttpStatus(input.status()).getCode(), //
                    "HTTP " + input.status(), List.of(), metadata);
        }
    }

    public void validateResponseHeaders(RequestPayload request, HttpResponse input) throws IOException {
        GrpcMethodShape shape = this.shape(request);
        normalizedEncoding(input.header(shape.streaming() ? "connect-content-encoding" : "content-encoding"));
        String coding = this.coding(input.header("content-type"), shape.streaming());
        if (!StringUtils.equalsIgnoreCase(coding, request.getSerializeType())) {
            throw new ConnectStatusException("internal", "Connect response encoding differs from request", null, null);
        }
    }

    /** 已进入流式媒体类型的请求，即使尚未解析服务，也使用 EndStream 结束。 */
    public HttpResponse error(HttpRequest input, Throwable failure) {
        if (failure instanceof ConnectHttpStatusException) {
            return this.error(failure);
        }
        String media = input.header("content-type");
        if (media != null) {
            String type = media.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            boolean streaming = StringUtils.equals(type, "application/connect+json") //
                    || StringUtils.equals(type, "application/connect+proto");
            if (streaming) {
                Map<String, List<String>> headers = Map.of("content-type", List.of(type));
                try {
                    return HttpResponse.newBuffered(200, headers, this.end(failure), Map.of());
                } catch (IOException unexpected) {
                    throw new IllegalStateException("Cannot encode Connect end-stream error", unexpected);
                }
            }
        }
        return this.error(failure);
    }

    public HttpResponse error(Throwable failure) {
        return this.error(failure, null);
    }

    public HttpResponse error(Throwable failure, ResponsePayload response) {
        ConnectStatusException error = this.status(failure);
        if (response != null && failure instanceof IOException && ConnectStatusException.find(failure) == null) {
            error = new ConnectStatusException("unknown", failure.getMessage(), null, null);
        }
        Map<String, List<String>> headers = response == null ? new LinkedHashMap<>() : this.metadata(response);
        if (response != null) {
            headers.putAll(ConnectMetadata.from(response).getHeaders());
            for (Map.Entry<String, List<String>> field : ConnectMetadata.from(response).getTrailers().entrySet()) {
                headers.put("trailer-" + field.getKey(), field.getValue());
            }
        }
        for (Map.Entry<String, List<String>> field : error.getMetadata().entrySet()) {
            headers.put("trailer-" + field.getKey(), field.getValue());
        }
        headers.put("content-type", List.of("application/json"));
        int http = error.httpStatus();
        if (failure instanceof ConnectHttpStatusException httpFailure) {
            http = httpFailure.getHttpStatus();
            if (http == 405) {
                headers.put("allow", List.of("POST"));
            }
        }
        try {
            return HttpResponse.newBuffered(http, headers, this.frames.error(error), Map.of());
        } catch (IOException unexpected) {
            throw new IllegalStateException("Cannot encode Connect error", unexpected);
        }
    }

    public byte[] end(ResponsePayload response) throws IOException {
        Throwable failure = response.getFailure();
        if (failure == null && response.getStatus() != ProtocolStatus.OK) {
            failure = new MetException(response.getStatus(), response.getOption("message"));
        }
        Map<String, List<String>> trailers = this.metadata(response);
        trailers.putAll(ConnectMetadata.from(response).getTrailers());
        ConnectStatusException error = failure == null ? null : this.status(failure);
        return this.frames.end(error, trailers);
    }

    public byte[] end(Throwable failure) throws IOException {
        return this.frames.end(this.status(failure), Map.of());
    }

    public ConnectEnvelope readEnvelope(InputStream input, String encoding) throws IOException {
        return this.frames.read(input, encoding);
    }

    public byte[] frame(byte[] message, String encoding) throws IOException {
        return this.frames.frame(message, encoding);
    }

    public String requestCompression() {
        return this.compression;
    }

    public String responseCompression(RequestPayload request) {
        String accepted = request.getOption("connect-accept-encoding");
        if (accepted != null && StringUtils.equals(this.compression, "gzip")) {
            for (String candidate : accepted.split(",")) {
                if (StringUtils.equalsIgnoreCase(candidate.trim(), "gzip")) {
                    return "gzip";
                }
            }
        }
        return "identity";
    }

    public Map<String, List<String>> responseHeaders(RequestPayload request, ResponsePayload response) {
        GrpcMethodShape shape = this.shape(request);
        Map<String, List<String>> headers = response == null || shape.streaming() //
                ? new LinkedHashMap<>() : this.metadata(response);
        if (response != null) {
            headers.putAll(ConnectMetadata.from(response).getHeaders());
        }
        headers.put("content-type", List.of(contentType(request.getSerializeType(), shape.streaming())));
        String encoding = shape.streaming() ? "connect-content-encoding" : "content-encoding";
        String accept = shape.streaming() ? "connect-accept-encoding" : "accept-encoding";
        headers.put(encoding, List.of(this.responseCompression(request)));
        headers.put(accept, List.of("identity,gzip"));
        return headers;
    }

    public void readHeaders(Map<String, List<String>> fields, MetOptionSet options) {
        this.receive(fields, options, false);
    }

    public void readEnd(Map<String, List<String>> fields, MetOptionSet options) {
        this.receive(fields, options, true);
    }

    private void readResponseHeaders(Map<String, List<String>> fields, MetOptionSet options) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Map<String, List<String>> trailers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (name.startsWith("trailer-")) {
                trailers.put(name.substring(8), field.getValue());
            } else {
                headers.put(name, field.getValue());
            }
        }
        this.readHeaders(headers, options);
        this.readEnd(trailers, options);
    }

    private void receive(Map<String, List<String>> fields, MetOptionSet options, boolean trailers) {
        ConnectMetadata metadata = ConnectMetadata.from(options);
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (!applicationKey(name)) {
                continue;
            }
            for (String value : field.getValue()) {
                if (name.endsWith("-bin")) {
                    for (String item : value.split(",", -1)) {
                        byte[] bytes = Base64.getDecoder().decode(item.trim());
                        if (trailers) {
                            metadata.addBinaryTrailer(name, bytes);
                        } else {
                            metadata.addBinaryHeader(name, bytes);
                        }
                    }
                } else if (trailers) {
                    metadata.addTrailer(name, value);
                } else {
                    metadata.addHeader(name, value);
                }
            }
            if (!field.getValue().isEmpty()) {
                options.addOption(name, field.getValue().get(0));
            }
        }
    }

    private Map<String, List<String>> requestHeaders(RequestPayload request, boolean streaming) {
        Map<String, List<String>> headers = this.metadata(request);
        headers.putAll(ConnectMetadata.from(request).getHeaders());
        headers.put("content-type", List.of(contentType(request.getSerializeType(), streaming)));
        headers.put("connect-protocol-version", List.of("1"));
        headers.put("connect-timeout-ms", List.of(String.valueOf(Math.max(1, request.getClientTimeout()))));
        headers.put(streaming ? "connect-content-encoding" : "content-encoding", List.of(this.compression));
        headers.put(streaming ? "connect-accept-encoding" : "accept-encoding", List.of("identity,gzip"));
        return headers;
    }

    private Map<String, List<String>> metadata(MetOptionSet options) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String value = options.getOption(key);
            String name = key.toLowerCase(Locale.ROOT);
            if (value != null && applicationKey(name)) {
                fields.put(name, List.of(value));
            }
        }
        return fields;
    }

    private RouteMount mount(HttpRequest input, String path) {
        RouteSelection selected = input.route();
        if (selected != null) {
            if (!StringUtils.equals(selected.protocolName(), this.config.name())) {
                throw invalid("Connect request belongs to another mount");
            }
            return selected.mount();
        }
        int separator = path.indexOf('/', 1);
        if (separator > 1) {
            MetBindInfo<?> service = this.context.getServiceInfo(this.config.name(), path.substring(1, separator));
            if (service != null && this.routes.accepts(service) && this.routes.exactPaths(service).contains(path)) {
                return RouteMount.exact(path);
            }
        }
        return this.routes.match(path);
    }

    private void validateGet(MetBindInfo<?> service, Method method, HttpRequest input, Map<String, String> query) {
        if (this.definition(service).shape(method).streaming()) {
            throw new ConnectHttpStatusException(405, "Connect GET supports only unary methods");
        }
        this.sideEffectFree(service, method);
        String connect = query.get("connect");
        if (connect != null && !StringUtils.equals(connect, "v1")) {
            throw invalid("Unsupported Connect GET protocol version");
        }
        if ((input.stream() == null && input.body().length != 0) || !query.containsKey("message")) {
            throw invalid("Connect GET requires a message query and an empty body");
        }
        String encoding = normalizedEncoding(query.get("compression"));
        boolean protobuf = StringUtils.equals(query.get("encoding"), "proto");
        boolean compressed = !StringUtils.equals(encoding, "identity");
        boolean binary = protobuf || compressed;
        if (binary && !StringUtils.equals(query.get("base64"), "1")) {
            throw invalid("Binary Connect GET messages require base64=1");
        }
    }

    private void sideEffectFree(MetBindInfo<?> service, Method method) {
        MethodDescriptor descriptor = this.definition(service).descriptor(method);
        if (descriptor.getOptions().getIdempotencyLevel() != IdempotencyLevel.NO_SIDE_EFFECTS) {
            throw new ConnectHttpStatusException(405, "Connect GET requires a NO_SIDE_EFFECTS proto method");
        }
    }

    private String queryCoding(Map<String, String> query) {
        String value = query.get("encoding");
        if (StringUtils.equals(value, "json")) {
            return "Json";
        }
        if (StringUtils.equals(value, "proto")) {
            return "Protobuf";
        }
        throw new ConnectHttpStatusException(415, "Unsupported Connect GET encoding: " + value);
    }

    private String coding(String media, boolean streaming) {
        if (media != null) {
            String expected = streaming ? "application/connect+" : "application/";
            String name = media(media);
            String lower = media.toLowerCase(Locale.ROOT);
            for (String parameter : lower.split(";")) {
                String field = parameter.trim();
                if (field.startsWith("charset=")) {
                    String charset = field.substring(8).replace("\"", "").trim();
                    boolean utf8 = StringUtils.equals(charset, "utf-8") || StringUtils.equals(charset, "utf8");
                    if (!utf8 || !name.endsWith("json")) {
                        throw new ConnectHttpStatusException(415, "Connect JSON requires UTF-8");
                    }
                }
            }
            if (StringUtils.equals(name, expected + "proto")) {
                return "Protobuf";
            }
            if (StringUtils.equals(name, expected + "json")) {
                return "Json";
            }
        }
        throw new ConnectHttpStatusException(415, "Unsupported Connect content-type: " + media);
    }

    private static String contentType(String coding, boolean streaming) {
        String suffix;
        if (StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            suffix = "proto";
        } else if (StringUtils.equalsIgnoreCase(coding, "Json")) {
            suffix = "json";
        } else {
            throw new IllegalArgumentException("Connect supports Json or Protobuf messages");
        }
        return (streaming ? "application/connect+" : "application/") + suffix;
    }

    private GrpcServiceBinding definition(MetBindInfo<?> service) {
        ProtocolBinding binding = this.routes.binding(service);
        if (binding == null || !(binding.getDefinition() instanceof GrpcServiceBinding definition)) {
            throw new UnsupportedOperationException("Service has no prepared Connect binding: " + service.getBindID());
        }
        return definition;
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        String version = request.getServiceVersion();
        MetBindInfo<?> service = this.context.getServiceInfo(group, name, version);
        if (service == null || !this.routes.accepts(service)) {
            throw new UnsupportedOperationException("Service is not exposed on this protocol endpoint: " + name);
        }
        return service;
    }

    private String alias(MetBindInfo<?> service) {
        String alias = service.getAliasName(this.config.name());
        if (StringUtils.isBlank(alias)) {
            throw invalid("Service must declare its Connect alias");
        }
        return alias;
    }

    private Method findMethod(MetBindInfo<?> service, String name) {
        return this.definition(service).javaMethod(name);
    }

    private int timeout(String value) {
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        if (!value.matches("[0-9]{1,10}")) {
            throw invalid("Invalid connect-timeout-ms");
        }
        long millis = Long.parseLong(value);
        if (millis == 0) {
            throw invalid("connect-timeout-ms must be positive");
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private int httpVersion() {
        return this.config.integer("httpVersion", 1);
    }

    private ConnectStatusException status(Throwable failure) {
        ConnectStatusException existing = ConnectStatusException.find(failure);
        if (existing != null) {
            return existing;
        }
        String code = "unknown";
        if (failure instanceof ConnectHttpStatusException) {
            code = "unimplemented";
        } else if (failure instanceof CancellationException) {
            code = "canceled";
        } else if (failure instanceof TimeoutException || failure instanceof MetTimeoutException) {
            code = "deadline_exceeded";
        } else if (failure instanceof UnsupportedOperationException) {
            code = "unimplemented";
        } else if (failure instanceof IllegalArgumentException || failure instanceof IOException) {
            code = "invalid_argument";
        } else if (failure instanceof MetException met) {
            code = switch (met.getStatus()) {
                case ProtocolStatus.Timeout -> "deadline_exceeded";
                case ProtocolStatus.Forbidden -> "permission_denied";
                case ProtocolStatus.Unauthorized -> "unauthenticated";
                case ProtocolStatus.NotFound -> "unimplemented";
                case ProtocolStatus.QueueFull, ProtocolStatus.SendLimitPolicy -> "resource_exhausted";
                case ProtocolStatus.NetworkError -> "unavailable";
                default -> "internal";
            };
        }
        return new ConnectStatusException(code, failure.getMessage(), null, null);
    }

    private static boolean applicationKey(String name) {
        return name.matches("[0-9a-z_.-]+") && !name.startsWith("connect-") && !HTTP_FIELDS.contains(name);
    }

    private static Map<String, String> query(String uri) {
        int index = uri.indexOf('?');
        Map<String, String> fields = new LinkedHashMap<>();
        if (index < 0) {
            return fields;
        }
        for (String part : uri.substring(index + 1).split("&", -1)) {
            int separator = part.indexOf('=');
            String key = queryPart(separator < 0 ? part : part.substring(0, separator));
            String value = queryPart(separator < 0 ? "" : part.substring(separator + 1));
            boolean reserved = Set.of("message", "encoding", "compression", "base64", "connect").contains(key);
            if (fields.putIfAbsent(key, value) != null && reserved) {
                throw invalid("Duplicate Connect GET parameter: " + key);
            }
        }
        return fields;
    }

    private static String queryPart(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < value.length(); ) {
            char item = value.charAt(index);
            if (item == '%') {
                if (index + 2 >= value.length()) {
                    throw invalid("Invalid Connect GET percent encoding");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalid("Invalid Connect GET percent encoding");
                }
                bytes.write(high * 16 + low);
                index += 3;
            } else {
                int point = value.codePointAt(index);
                String text = item == '+' ? " " : new String(Character.toChars(point));
                bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
                index += Character.charCount(point);
            }
        }
        try {
            return utf8(bytes.toByteArray());
        } catch (CharacterCodingException failure) {
            throw invalid("Connect GET query must contain valid UTF-8");
        }
    }

    private static String media(String contentType) {
        return contentType == null ? null : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String path(String uri) {
        if (uri.indexOf('#') >= 0) {
            throw invalid("Connect paths must not contain fragments");
        }
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    private static String normalizedEncoding(String value) {
        String normalized = value == null ? "identity" : value.toLowerCase(Locale.ROOT);
        if (!StringUtils.equals(normalized, "identity") && !StringUtils.equals(normalized, "gzip")) {
            throw new ConnectStatusException("unimplemented", "Supported Connect encodings: identity,gzip", null, null);
        }
        return normalized;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT) //
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static ConnectStatusException invalid(String message) {
        return new ConnectStatusException("invalid_argument", message, null, null);
    }
}
