/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPInputStream;
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
    private              long           requestId;

    GrpcCodec(ProtocolConfig config, MetContext context) {
        this.config = config;
        this.context = context;
        this.limit = config.integer("maxMessageSize", 4 * 1024 * 1024);
    }

    RequestPayload receive(HttpRequest input) throws Exception {
        RequestPayload request = this.open(input);
        this.readRequest(request, input);
        return request;
    }

    void readRequest(RequestPayload request, HttpRequest input) throws Exception {
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] message = this.unframe(input.body(), input.headers().get("grpc-encoding"));
        Object argument = this.decode(service, method, request.getSerializeType(), message, true);
        request.addParameter(method.getParameterTypes()[0].getName(), argument);
    }

    RequestPayload open(HttpRequest input) throws Exception {
        if (!StringUtils.equals("POST", input.method())) {
            throw new IllegalArgumentException("gRPC requires POST");
        }
        String prefix = this.prefix();
        String path = input.uri();
        if (!path.startsWith(prefix)) {
            throw new IllegalArgumentException("gRPC path is outside its mount");
        }
        String[] parts = path.substring(prefix.length()).split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected /service/method");
        }
        MetBindInfo<?> service = this.context.getServiceInfo("grpc", parts[0]);
        if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
            throw new UnsupportedOperationException("Unknown gRPC service: " + parts[0]);
        }
        if (!this.config.accepts(service.getBindProtocols())) {
            throw new UnsupportedOperationException("Service is not exposed on this protocol mount");
        }
        Method method = this.findMethod(service, parts[1]);
        String defaultCoding = service.getSerializeType();
        if (StringUtils.isBlank(defaultCoding)) {
            defaultCoding = this.config.option("serializeType", "Protobuf");
        }
        String coding = this.coding(input.headers().get("content-type"), defaultCoding);
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
        request.setClientTimeout(this.timeout(input.headers().get("grpc-timeout")));
        this.readMetadata(input.headers(), request);
        return request;
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
        String path = this.prefix() + this.alias(service) + "/" + method.getName();
        Map<String, String> headers = this.metadata(request);
        headers.put("content-type", this.contentType(coding));
        headers.put("te", "trailers");
        headers.put("grpc-timeout", this.timeoutHeader(request.getClientTimeout()));
        headers.put("grpc-accept-encoding", "identity,gzip");
        return HttpRequest.newBuffered("POST", path, headers, this.frame(body), 2);
    }

    HttpRequest streamRequest(RequestPayload request, HttpBody body) {
        MetBindInfo<?> service = this.service(request);
        String path = this.prefix() + this.alias(service) + "/" + request.getTargetMethod();
        Map<String, String> headers = this.metadata(request);
        headers.put("content-type", this.contentType(request.getSerializeType()));
        headers.put("grpc-accept-encoding", "identity,gzip");
        headers.put("te", "trailers");
        headers.put("grpc-timeout", this.timeoutHeader(request.getClientTimeout()));
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
        Descriptor descriptor = this.descriptor(this.service(request), this.target(request), parameter);
        DynamicMessage message = DynamicMessage.parseFrom(descriptor, bytes);
        String json = JsonFormat.printer().includingDefaultValueFields().print(message);
        return MAPPER.readValue(json, type);
    }

    HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
        if (response.getStatus() != ProtocolStatus.OK) {
            return this.failure(this.grpcStatus(response.getStatus()), response.getOption("message"));
        }
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] body = this.encode(service, method, request.getSerializeType(), response.getReturnData(), false);
        Map<String, String> trailers = this.metadata(response);
        trailers.put("grpc-status", "0");
        Map<String, String> headers = Map.of("content-type", this.contentType(request.getSerializeType()));
        return HttpResponse.newBuffered(200, headers, this.frame(body), trailers);
    }

    ResponsePayload decode(RequestPayload request, HttpResponse input) throws Exception {
        this.validateResponse(request, input, input.trailers());
        String coding = request.getSerializeType();
        MetBindInfo<?> service = this.service(request);
        Method method = this.method(service, request.getTargetMethod());
        byte[] body = this.unframe(input.body(), input.headers().get("grpc-encoding"));
        Object value = this.decode(service, method, coding, body, false);
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(coding);
        response.setReturnType(method.getReturnType().getName());
        response.setReturnData(value);
        this.readMetadata(input.headers(), response);
        this.readMetadata(input.trailers(), response);
        return response;
    }

    void validateResponse(RequestPayload request, HttpResponse input, Map<String, String> trailers) throws IOException {
        this.validateStatus(input, trailers);
        this.validateResponseHeaders(request, input);
    }

    void validateStatus(HttpResponse input, Map<String, String> trailers) throws IOException {
        String status = trailers.get("grpc-status");
        if (status == null) {
            status = input.headers().get("grpc-status");
        }
        if (input.status() != 200 || status == null) {
            throw new IOException("Invalid gRPC response: HTTP " + input.status() + ", grpc-status=" + status);
        }
        int code = Integer.parseInt(status);
        if (code != 0) {
            String message = trailers.getOrDefault("grpc-message", input.headers().get("grpc-message"));
            if (message != null) {
                message = URLDecoder.decode(message.replace("+", "%2B"), StandardCharsets.UTF_8);
            }
            String description = "gRPC status " + code + ": " + message;
            if (code == 4) {
                throw new MetTimeoutException(description);
            }
            throw new MetException(this.meteorStatus(code), description);
        }
    }

    void validateResponseHeaders(RequestPayload request, HttpResponse input) throws IOException {
        if (input.status() != 200) {
            throw new IOException("Invalid gRPC HTTP status: " + input.status());
        }
        String coding = this.coding(input.headers().get("content-type"), request.getSerializeType());
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
            throw new IOException("gRPC message exceeds configured maxMessageSize");
        }
        byte[] bytes = input.readNBytes((int) length);
        if (bytes.length != length) {
            throw new IOException("Truncated gRPC message");
        }
        return this.decompress(bytes, first, encoding);
    }

    HttpResponse error(Throwable failure) {
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

    private HttpResponse failure(int status, String message) {
        String text = message == null ? "RPC failed" : message;
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, String> trailers = Map.of("grpc-status", String.valueOf(status), "grpc-message", encoded);
        return HttpResponse.newBuffered(200, Map.of("content-type", "application/grpc"), new byte[0], trailers);
    }

    private byte[] encode(MetBindInfo<?> service, Method method, String coding, Object value, boolean request) throws Exception {
        if (!StringUtils.equalsIgnoreCase("Protobuf", coding)) {
            return this.coder(coding).encode(value);
        }
        Descriptor type = this.descriptor(service, method, request);
        DynamicMessage.Builder message = DynamicMessage.newBuilder(type);
        JsonFormat.parser().merge(MAPPER.writeValueAsString(value), message);
        return message.build().toByteArray();
    }

    private Object decode(MetBindInfo<?> service, Method method, String coding, byte[] body, boolean request) throws Exception {
        GrpcMethod signature = new GrpcMethod(method);
        Class<?> type = request ? signature.requestType() : signature.responseType();
        if (!StringUtils.equalsIgnoreCase("Protobuf", coding)) {
            return this.coder(coding).decode(body, type);
        }
        DynamicMessage message = DynamicMessage.parseFrom(this.descriptor(service, method, request), body);
        String json = JsonFormat.printer().includingDefaultValueFields().print(message);
        return MAPPER.readValue(json, type);
    }

    private Descriptor descriptor(MetBindInfo<?> service, Method method, boolean request) {
        if (!(service.getBindResource() instanceof GrpcProtoResource proto)) {
            String message = "Protobuf service must bind a GrpcProtoResource: " + this.alias(service);
            throw new IllegalArgumentException(message);
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

    private Map<String, String> metadata(MetOptionSet options) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            String value = options.getOption(key);
            if (key == null || value == null) {
                continue;
            }
            String name = key.toLowerCase(Locale.ROOT);
            if (this.isMetadata(name)) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    private void readMetadata(Map<String, String> fields, MetOptionSet options) {
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (this.isMetadata(name)) {
                options.addOption(name, field.getValue());
            }
        }
    }

    private boolean isMetadata(String name) {
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

    private String prefix() {
        String mount = this.config.option("contextPath", "/");
        return mount.endsWith("/") ? mount : mount + "/";
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
        if (message.length > this.limit) {
            throw new IOException("gRPC message exceeds configured maxMessageSize");
        }
        return ByteBuffer.allocate(5 + message.length).put((byte) 0).putInt(message.length).put(message).array();
    }

    private byte[] unframe(byte[] input, String encoding) throws IOException {
        if (input.length < 5) {
            throw new IOException("Truncated gRPC message prefix");
        }
        ByteBuffer frame = ByteBuffer.wrap(input);
        byte compressed = frame.get();
        long length = Integer.toUnsignedLong(frame.getInt());
        if (length != frame.remaining() || length > this.limit) {
            throw new IOException("Expected exactly one bounded gRPC message");
        }
        byte[] message = new byte[(int) length];
        frame.get(message);
        return this.decompress(message, compressed, encoding);
    }

    private byte[] decompress(byte[] message, int compressed, String encoding) throws IOException {
        if (compressed == 0) {
            return message;
        }
        if (compressed != 1 || !StringUtils.equalsIgnoreCase("gzip", encoding)) {
            throw new IOException("Unsupported gRPC message compression");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(message))) {
            byte[] expanded = gzip.readNBytes(this.limit + 1);
            if (expanded.length > this.limit) {
                throw new IOException("Decompressed gRPC message exceeds configured maxMessageSize");
            }
            return expanded;
        }
    }

    private String timeoutHeader(int millis) {
        if (millis <= 99_999_999) {
            return Math.max(1, millis) + "m";
        }
        return ((millis + 999L) / 1000) + "S";
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
            case ProtocolStatus.Forbidden, ProtocolStatus.Unauthorized -> 7;
            case ProtocolStatus.NotFound -> 12;
            case ProtocolStatus.QueueFull, ProtocolStatus.SendLimitPolicy -> 8;
            case ProtocolStatus.NetworkError -> 14;
            default -> 13;
        };
    }

    private short meteorStatus(int status) {
        return switch (status) {
            case 4 -> ProtocolStatus.Timeout;
            case 7, 16 -> ProtocolStatus.Forbidden;
            case 12 -> ProtocolStatus.NotFound;
            case 8 -> ProtocolStatus.QueueFull;
            case 14 -> ProtocolStatus.NetworkError;
            default -> ProtocolStatus.InvokeError;
        };
    }
}
