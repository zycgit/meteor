/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 在 HTTP 交换和 Meteor RPC 负载之间映射 Hprose 消息。 */
public final class HproseHttpCodec {
    private final MetContext           context;
    private final ProtocolConfig       protocol;
    private final HproseServiceCatalog services;

    public HproseHttpCodec(ProtocolConfig protocol, MetContext context) {
        this.context = context;
        this.protocol = protocol;
        this.services = new HproseServiceCatalog(context, protocol);
    }

    public HproseDecodedInvocation decodeRequest(HttpRequest request) throws Exception {
        Map<String, List<String>> headers = this.responseHeaders(request.header("Origin"));
        RouteMount mount = this.resolveMount(request);
        if (mount == null) {
            return this.immediate(404, headers, new byte[0]);
        }

        // 浏览器预检由协议直接响应，不创建业务调用。
        if (StringUtils.equalsIgnoreCase("OPTIONS", request.method())) {
            headers.put("Access-Control-Allow-Methods", List.of("GET, POST, OPTIONS"));
            String requested = request.header("Access-Control-Request-Headers");
            if (requested != null) {
                headers.put("Access-Control-Allow-Headers", List.of(requested));
            }
            return this.immediate(204, headers, new byte[0]);
        }

        boolean get = StringUtils.equalsIgnoreCase("GET", request.method());
        boolean post = StringUtils.equalsIgnoreCase("POST", request.method());
        if (!get && !post) {
            headers.put("Allow", List.of("GET, POST, OPTIONS"));
            return this.immediate(405, headers, new byte[0]);
        }
        if (request.body().length > this.protocol.integer("maxMessageSize", 4 * 1024 * 1024)) {
            throw new IOException("Hprose request exceeds maxMessageSize");
        }

        return this.decodeBody(request, mount, headers, get);
    }

    private RouteMount resolveMount(HttpRequest request) {
        RouteSelection route = request.route();
        if (route == null) {
            // 独立测试编解码器时回退到协议目录；实际请求由运行期路由表选定挂载点。
            return this.protocol.matchContext(request.uri());
        }
        if (!StringUtils.equals(this.protocol.mountId(), route.mountId())) {
            return null;
        }
        return route.mount();
    }

    private HproseDecodedInvocation decodeBody( //
            HttpRequest request, RouteMount mount, Map<String, List<String>> headers, boolean get) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(request.body());
        int tag = get ? 'z' : input.read();
        if (tag == 'z') {
            if (!get && input.read() != -1) {
                throw new IOException("Unexpected data after Hprose discovery");
            }
            return this.discovery(mount, headers);
        }
        if (tag != 'C') {
            throw new IOException("Invalid Hprose call tag: " + tag);
        }
        return this.decodeCalls(request, mount, input);
    }

    private HproseDecodedInvocation discovery(RouteMount mount, Map<String, List<String>> headers) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('F');
        new HproseWriter(output).writeArray(this.services.listFunctions(mount));
        output.write('z');
        return this.immediate(200, headers, output.toByteArray());
    }

    private HproseDecodedInvocation decodeCalls( //
            HttpRequest request, //
            RouteMount mount, //
            ByteArrayInputStream input) throws IOException {
        // 先解析完整报文，语法错误不能留下已经提交的半个批次。
        List<HproseDecodedCall> calls = new ArrayList<>();
        HproseReader reader = new HproseReader(input);
        int tag;
        do {
            if (calls.size() >= this.protocol.integer("maxBatchCalls", 1024)) {
                throw new IOException("Hprose batch exceeds maxBatchCalls");
            }

            reader.reset();
            String name = reader.readString();
            input.mark(0);
            tag = input.read();
            byte[] arguments = null;
            if (tag == 'a') {
                input.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                reader.readRaw(raw);
                arguments = raw.toByteArray();
                tag = input.read();
            }

            boolean byReference = tag == 't' && arguments != null;
            if (byReference) {
                tag = input.read();
            }
            if (tag != 'C' && tag != 'z') {
                throw new IOException("Invalid Hprose call terminator: " + tag);
            }

            // 单个方法解析失败只占用自己的响应位置，其余调用仍然可以执行。
            try {
                RequestPayload call = this.decodeCall(name, arguments, request, mount);
                calls.add(new HproseDecodedCall(call, byReference, null));
            } catch (Exception failure) {
                calls.add(new HproseDecodedCall(null, false, failure));
            }
        } while (tag == 'C');

        if (input.read() != -1) {
            throw new IOException("Unexpected data after Hprose call");
        }
        return new HproseDecodedInvocation(calls, null);
    }

    private RequestPayload decodeCall( //
            String name, byte[] arguments, HttpRequest request, RouteMount mount) throws IOException {
        int parameterCount = 0;
        HproseReader reader = null;
        if (arguments != null) {
            reader = new HproseReader(arguments);
            reader.checkTag('a');
            parameterCount = reader.readInt('{');
        }

        HproseServiceCatalog.ResolvedMethod resolved = this.services.resolve(name, parameterCount, mount);
        Method method = resolved.method();
        Object[] values = new Object[parameterCount];
        if (reader != null) {
            reader.readArray(method.getGenericParameterTypes(), values, parameterCount);
        }

        MetBindInfo<?> service = resolved.service();
        RequestPayload call = new RequestPayload();
        call.setServiceGroup(service.getBindGroup());
        call.setServiceName(service.getBindName());
        call.setServiceVersion(service.getBindVersion());
        call.setTargetMethod(method.getName());
        call.setSerializeType(HproseSerializeCoder.SERIALIZE_TYPE);
        call.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        call.setReceiveTime(System.currentTimeMillis());
        call.setMessage(service.isMessage() || method.isAnnotationPresent(MetMessage.class));

        for (Map.Entry<String, List<String>> field : request.headers().entrySet()) {
            if (!field.getValue().isEmpty()) {
                call.addOption(field.getKey(), field.getValue().get(0));
            }
        }
        call.addOption("Location", request.uri());
        call.addOption("Origin", request.header("Origin"));

        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < values.length; index++) {
            call.addParameter(types[index].getName(), values[index]);
        }
        return call;
    }

    private HproseDecodedInvocation immediate( //
            int status, Map<String, List<String>> headers, byte[] body) {
        HttpResponse response = HttpResponse.newBuffered(status, headers, body, Map.of());
        return new HproseDecodedInvocation(List.of(), response);
    }

    public byte[] encodeResponse(HproseDecodedCall call, ResponsePayload response) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HproseWireCodec.writeResponse(response.getRequestID(), response, output);
        byte[] bytes = output.toByteArray();
        output.reset();
        output.write(bytes, 0, bytes.length - 1);
        if (response.getStatus() == ProtocolStatus.OK && call.byReference()) {
            output.write('A');
            new HproseWriter(output).writeArray(call.request().getParameterValues().toArray());
        }
        return output.toByteArray();
    }

    public HttpResponse encodeBatchResponse(HttpRequest request, byte[][] responses) throws IOException {
        byte[] body = HproseWireCodec.encodeResponseBatch(responses);
        Map<String, List<String>> headers = this.responseHeaders(request.header("Origin"));
        return HttpResponse.newBuffered(200, headers, body, Map.of());
    }

    public byte[] encodeErrorPart(Throwable failure) {
        byte[] body = this.encodeErrorBody(failure);
        return Arrays.copyOf(body, body.length - 1);
    }

    public HttpRequest encodeRequest(RequestPayload request) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : request.getOptionKeys()) {
            headers.put(key, List.of(request.getOption(key)));
        }
        headers.put("Content-Type", List.of("application/hprose"));

        MetBindInfo<?> service = this.context.getServiceInfo( //
                request.getServiceGroup(), request.getServiceName(), request.getServiceVersion());
        String alias = service.getAliasName(HproseSerializeCoder.SERIALIZE_TYPE);
        String uri = this.protocol.contextPath(service);
        byte[] body = HproseWireCodec.encodeRequest(alias, request);
        return HttpRequest.newBuffered("POST", uri, headers, body, 1, Map.of());
    }

    public ResponsePayload decodeResponse(RequestPayload request, HttpResponse response) throws Exception {
        if (response.body().length > this.protocol.integer("maxMessageSize", 4 * 1024 * 1024)) {
            throw new IOException("Hprose response exceeds maxMessageSize");
        }

        ResponsePayload result = new ResponsePayload();
        result.setRequestID(request.getRequestID());
        result.setReceiveTime(System.currentTimeMillis());
        result.setSerializeType(HproseSerializeCoder.SERIALIZE_TYPE);
        result.setStatus((short) response.status());
        this.copyHeaders(response, result);

        if (response.status() != 200) {
            result.addOption("message", "HTTP status " + response.status());
            return result;
        }
        if (response.body().length > 0 && response.body()[0] == 'E') {
            this.decodeRemoteError(response.body(), result);
        } else if (request.isMessage()) {
            this.decodeAcknowledgement(response.body(), result);
        } else {
            this.decodeResult(request, response.body(), result);
        }
        return result;
    }

    private void copyHeaders(HttpResponse response, ResponsePayload result) {
        for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
            if (!header.getValue().isEmpty()) {
                result.addOption(header.getKey(), header.getValue().get(0));
            }
        }
    }

    private void decodeRemoteError(byte[] body, ResponsePayload result) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        input.read();
        String error = new HproseReader(input).readString();
        if (input.read() != 'z' || input.read() != -1) {
            throw new IOException("Invalid Hprose error terminator");
        }

        result.setStatus(ProtocolStatus.InvokeError);
        result.addOption("message", error);
        // Meteor 对端会在错误文本中携带状态和响应选项，其他 Hprose 对端仍可返回普通文本。
        try {
            JsonNode details = HproseWireCodec.decodeErrorDetails(error);
            if (details == null || !details.isObject() || !details.hasNonNull("status")) {
                return;
            }
            short status = Short.parseShort(details.get("status").asText());
            if (status != ProtocolStatus.OK && status != ProtocolStatus.Accept) {
                result.setStatus(status);
            }
            if (details.hasNonNull("message")) {
                result.addOption("message", details.get("message").asText());
            }
        } catch (IOException | NumberFormatException ignored) {
            // 普通 Hprose 对端返回的错误文本不要求符合 Meteor 扩展格式。
        }
    }

    private void decodeAcknowledgement(byte[] body, ResponsePayload result) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        if (input.read() != 'R') {
            throw new IOException("Invalid Hprose acknowledgement tag");
        }

        // 消息调用只消费远端确认，不把确认正文映射成业务返回值。
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        new HproseReader(input).readRaw(ignored);
        if (input.read() != 'z' || input.read() != -1) {
            throw new IOException("Invalid Hprose acknowledgement terminator");
        }
        result.setStatus(ProtocolStatus.Accept);
    }

    private void decodeResult(RequestPayload request, byte[] body, ResponsePayload result) throws Exception {
        MetBindInfo<?> service = this.context.getServiceInfo( //
                request.getServiceGroup(), request.getServiceName(), request.getServiceVersion());
        Class<?>[] parameterTypes = new Class<?>[request.getParameterTypes().size()];
        for (int index = 0; index < parameterTypes.length; index++) {
            String type = request.getParameterTypes().get(index);
            parameterTypes[index] = MetRuntimeUtils.toJavaType(type, this.context.getClassLoader());
        }

        Method method = MetRuntimeUtils.getServiceMethod( //
                service.getBindType(), request.getTargetMethod(), parameterTypes);
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        if (input.read() != 'R') {
            throw new IOException("Invalid Hprose result tag");
        }
        Object value = new HproseReader(input).unserialize(method.getGenericReturnType());
        if (input.read() != 'z' || input.read() != -1) {
            throw new IOException("Invalid Hprose response terminator");
        }

        result.setReturnType(method.getReturnType().getName());
        result.setReturnData(value);
    }

    public HttpResponse encodeErrorResponse(HttpRequest request, Throwable error) {
        byte[] body = this.encodeErrorBody(error);
        Map<String, List<String>> headers = this.responseHeaders(request.header("Origin"));
        return HttpResponse.newBuffered(200, headers, body, Map.of());
    }

    private byte[] encodeErrorBody(Throwable error) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (error instanceof MetException status) {
                ResponsePayload response = new ResponsePayload();
                response.setStatus(status.getStatus());
                response.addOption("message", status.getMessage());
                HproseWireCodec.writeResponse(0, response, output);
            } else {
                output.write('E');
                new HproseWriter(output).writeString(String.valueOf(error.getMessage()));
                output.write('z');
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, List<String>> responseHeaders(String origin) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/hprose"));
        boolean specified = origin != null && !StringUtils.equals("null", origin);
        headers.put("Access-Control-Allow-Origin", List.of(specified ? origin : "*"));
        if (specified) {
            headers.put("Access-Control-Allow-Credentials", List.of("true"));
            headers.put("Vary", List.of("Origin"));
        }
        return headers;
    }
}
