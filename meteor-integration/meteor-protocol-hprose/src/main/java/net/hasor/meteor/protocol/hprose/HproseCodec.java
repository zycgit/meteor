/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ContextPaths;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** HTTP/Hprose request and response mapping. */
final class HproseCodec {
    private final MetContext     context;
    // Hprose carries no request ID; these IDs correlate inbound HTTP requests with their replies.
    private final ProtocolConfig protocol;
    private final AtomicLong     incomingIds = new AtomicLong();

    public HproseCodec(ProtocolConfig config, MetContext context) {
        this.context = context;
        this.protocol = config;
    }

    public HproseInvocation receive(HttpRequest request) throws Exception {
        ContextRoutes.Route route = request.route();
        ContextPaths.Mount mount;
        if (route != null) {
            if (!StringUtils.equals(this.protocol.scheme(), route.protocol())) {
                return this.immediate(404, this.headers(request.header("Origin")), new byte[0]);
            }
            mount = route.mount();
        } else {
            // 直接使用 codec 时只回退协议目录；服务精确入口由路由表选择。
            mount = this.protocol.matchContext(request.uri());
        }
        Map<String, List<String>> headers = this.headers(request.header("Origin"));
        if (mount == null) {
            return this.immediate(404, headers, new byte[0]);
        }

        // HTTP 发现和浏览器预检由协议处理，不创建业务调用。
        if (StringUtils.equalsIgnoreCase("OPTIONS", request.method())) {
            headers.put("Access-Control-Allow-Methods", List.of("GET, POST, OPTIONS"));
            String requested = request.header("Access-Control-Request-Headers");
            if (requested != null) {
                headers.put("Access-Control-Allow-Headers", List.of(requested));
            }
            return this.immediate(204, headers, new byte[0]);
        }

        boolean get = StringUtils.equalsIgnoreCase("GET", request.method());
        if (!get && !StringUtils.equalsIgnoreCase("POST", request.method())) {
            headers.put("Allow", List.of("GET, POST, OPTIONS"));
            return this.immediate(405, headers, new byte[0]);
        }

        if (request.body().length > this.protocol.integer("maxMessageSize", 4 * 1024 * 1024)) {
            throw new IOException("Hprose request exceeds maxMessageSize");
        }

        ByteArrayInputStream input = new ByteArrayInputStream(request.body());
        int tag = get ? 'z' : input.read();
        if (tag == 'z') {
            if (!get && input.read() != -1) {
                throw new IOException("Unexpected data after Hprose discovery");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write('F');
            new HproseWriter(output).writeArray(HproseUtils.doFunction(this.context, this.protocol, mount));
            output.write('z');
            return this.immediate(200, headers, output.toByteArray());
        }

        if (tag != 'C') {
            throw new IOException("Invalid Hprose call tag: " + tag);
        }

        // 先解析完整报文；语法错误不能留下已经提交的半个批次。
        List<HproseCall> calls = new ArrayList<>();
        HproseReader reader = new HproseReader(input);
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

            // 一个方法不存在只影响该位置，其余调用仍能交给 Dispatcher。
            try {
                RequestPayload call = this.readCall(name, arguments, request, mount);
                calls.add(new HproseCall(call, byReference, null));
            } catch (Exception failure) {
                calls.add(new HproseCall(null, false, failure));
            }
        } while (tag == 'C');
        if (input.read() != -1) {
            throw new IOException("Unexpected data after Hprose call");
        }
        return new HproseInvocation(List.copyOf(calls), headers, null);
    }

    private RequestPayload readCall(String name, byte[] arguments, HttpRequest input, ContextPaths.Mount mount) throws IOException {
        int count = 0;
        HproseReader reader = null;
        if (arguments != null) {
            reader = new HproseReader(arguments);
            reader.checkTag('a');
            count = reader.readInt('{');
        }

        // Hprose 函数名不区分大小写；通过完整别名匹配，避免下划线歧义。
        MetBindInfo<?> selected = null;
        Method method = null;
        boolean forbidden = false;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
                continue;
            }
            String alias = service.getAliasName(HproseConstants.HPROSE);
            if (StringUtils.isBlank(alias)) {
                continue;
            }

            for (Method candidate : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                String exposed = alias + "_" + candidate.getName();
                if (candidate.getParameterCount() != count || !StringUtils.equalsIgnoreCase(exposed, name)) {
                    continue;
                }
                if (!this.protocol.accepts(service, mount)) {
                    forbidden = true;
                    continue;
                }
                if (method != null) {
                    throw new MetException(ProtocolStatus.ProtocolError, "Ambiguous Hprose method: " + name);
                }
                selected = service;
                method = candidate;
            }
        }

        if (method == null) {
            if (forbidden) {
                throw new MetException(ProtocolStatus.Forbidden, "Service is not exposed on this protocol mount");
            }
            throw new MetException(ProtocolStatus.NotFound, "Hprose method not found: " + name + "/" + count);
        }

        Object[] values = new Object[count];
        if (reader != null) {
            reader.readArray(method.getGenericParameterTypes(), values, count);
        }

        RequestPayload call = new RequestPayload();
        call.setRequestID(this.incomingIds.incrementAndGet());
        call.setServiceGroup(selected.getBindGroup());
        call.setServiceName(selected.getBindName());
        call.setServiceVersion(selected.getBindVersion());
        call.setTargetMethod(method.getName());
        call.setSerializeType(HproseConstants.HPROSE);
        call.setClientTimeout(this.context.getSettings().getDefaultTimeout());
        call.setReceiveTime(System.currentTimeMillis());
        for (Map.Entry<String, List<String>> field : input.headers().entrySet()) {
            if (!field.getValue().isEmpty()) {
                call.addOption(field.getKey(), field.getValue().get(0));
            }
        }
        call.addOption("Location", input.uri());
        call.addOption("Origin", input.header("Origin"));
        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < values.length; index++) {
            call.addParameter(types[index].getName(), values[index]);
        }
        return call;
    }

    private HproseInvocation immediate(int status, Map<String, List<String>> headers, byte[] body) {
        HttpResponse response = HttpResponse.newBuffered(status, headers, body, Map.of());
        return new HproseInvocation(List.of(), headers, response);
    }

    public byte[] encodeResult(HproseCall call, ResponsePayload response) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HproseUtils.parseResponse(response.getRequestID(), response, output);
        byte[] bytes = output.toByteArray();
        output.reset();
        output.write(bytes, 0, bytes.length - 1);
        if (response.getStatus() == ProtocolStatus.OK && call.byReference()) {
            output.write('A');
            new HproseWriter(output).writeArray(call.request().getParameterValues().toArray());
        }
        return output.toByteArray();
    }

    public byte[] encodeError(Throwable failure) {
        byte[] body = this.error(failure).body();
        return Arrays.copyOf(body, body.length - 1);
    }

    public HttpRequest encode(RequestPayload request) throws Exception {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String key : request.getOptionKeys()) {
            headers.put(key, List.of(request.getOption(key)));
        }

        headers.put("Content-Type", List.of("application/hprose"));
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        String version = request.getServiceVersion();
        MetBindInfo<?> service = this.context.getServiceInfo(group, name, version);
        String uri = this.protocol.contextPath(service);
        byte[] body = HproseUtils.encodeRequest(this.context, request);
        return HttpRequest.newBuffered("POST", uri, headers, body, 1, Map.of());
    }

    public ResponsePayload decode(RequestPayload request, HttpResponse response) throws Exception {
        if (response.body().length > this.protocol.integer("maxMessageSize", 4 * 1024 * 1024)) {
            throw new IOException("Hprose response exceeds maxMessageSize");
        }

        ResponsePayload result = new ResponsePayload();
        result.setRequestID(request.getRequestID());
        result.setReceiveTime(System.currentTimeMillis());
        result.setSerializeType("Hprose");
        result.setStatus((short) response.status());
        for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
            if (!header.getValue().isEmpty()) {
                result.addOption(header.getKey(), header.getValue().get(0));
            }
        }

        if (response.status() == 200) {
            if (response.body().length > 0 && response.body()[0] == 'E') {
                ByteArrayInputStream input = new ByteArrayInputStream(response.body());
                input.read();
                String error = new HproseReader(input).readString();
                if (input.read() != 'z' || input.read() != -1) {
                    throw new IOException("Invalid Hprose error terminator");
                }
                result.setStatus(ProtocolStatus.InvokeError);
                result.addOption("message", error);
                // RSF peers carry a status and options in the Hprose error string.
                try {
                    JsonNode details = HproseUtils.parseError(error);
                    if (details != null && details.isObject() && details.hasNonNull("status")) {
                        short status = Short.parseShort(details.get("status").asText());
                        if (status != ProtocolStatus.OK && status != ProtocolStatus.Accept) {
                            result.setStatus(status);
                        }
                        if (details.hasNonNull("message")) {
                            result.addOption("message", details.get("message").asText());
                        }
                    }
                } catch (IOException | NumberFormatException ignored) {
                    /* Other Hprose peers send plain error strings. */
                }
            } else {
                String group = request.getServiceGroup();
                String name = request.getServiceName();
                String version = request.getServiceVersion();
                MetBindInfo<?> service = this.context.getServiceInfo(group, name, version);
                Class<?>[] types = new Class<?>[request.getParameterTypes().size()];
                for (int index = 0; index < types.length; index++) {
                    String type = request.getParameterTypes().get(index);
                    types[index] = MetRuntimeUtils.toJavaType(type, this.context.getClassLoader());
                }

                Method method = MetRuntimeUtils.getServiceMethod(service.getBindType(), request.getTargetMethod(), types);
                ByteArrayInputStream input = new ByteArrayInputStream(response.body());
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
        } else {
            result.addOption("message", "HTTP status " + response.status());
        }
        return result;
    }

    public HttpResponse error(Throwable error) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (error instanceof MetException status) {
                ResponsePayload response = new ResponsePayload();
                response.setStatus(status.getStatus());
                response.addOption("message", status.getMessage());
                HproseUtils.parseResponse(0, response, output);
            } else {
                output.write('E');
                new HproseWriter(output).writeString(String.valueOf(error.getMessage()));
                output.write('z');
            }
            return HttpResponse.newBuffered(200, this.headers(null), output.toByteArray(), Map.of());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, List<String>> headers(String origin) {
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
