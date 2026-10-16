/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcException;

/** JSON-RPC 2.0 信封、参数和结果映射，不持有网络交换或调用生命周期。 */
public final class JsonRpcCodec {
    private static final ObjectMapper JSON = JsonMapper.builder() //
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) //
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS) //
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT) //
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS) //
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true)) //
            .build();

    private final ProtocolConfig protocol;
    private final MetContext     context;
    private final int            maxMessageSize;
    private final int            maxBatchCalls;

    public JsonRpcCodec(ProtocolConfig protocol, MetContext context) {
        this.protocol = protocol;
        this.context = context;
        this.maxMessageSize = protocol.integer("maxMessageSize", 4 * 1024 * 1024);
        this.maxBatchCalls = protocol.integer("maxBatchCalls", 1024);
    }

    /** 完整文档先通过语法和数量限制检查，再解析各个调用。 */
    public JsonRpcEnvelope decodeRequest(byte[] body, RouteMount mount) {
        if (body.length > this.maxMessageSize) {
            return this.invalidEnvelope(-32600, "Invalid Request");
        }
        JsonNode document;
        try {
            document = JSON.readTree(body);
        } catch (IOException failure) {
            return this.invalidEnvelope(-32700, "Parse error");
        }
        if (document == null || document.isMissingNode()) {
            return this.invalidEnvelope(-32700, "Parse error");
        }

        // 数组中的结构错误各自返回错误；空数组和超限批次作为整个请求失败。
        boolean batch = document.isArray();
        if (batch && (document.isEmpty() || document.size() > this.maxBatchCalls)) {
            return this.invalidEnvelope(-32600, "Invalid Request");
        }
        List<JsonRpcCall> calls = new ArrayList<>();
        if (batch) {
            for (JsonNode item : document) {
                calls.add(this.decodeCall(item, mount));
            }
        } else {
            calls.add(this.decodeCall(document, mount));
        }
        return new JsonRpcEnvelope(batch, calls);
    }

    private JsonRpcEnvelope invalidEnvelope(int code, String message) {
        JsonNode response = error(NullNode.instance, code, message, null);
        JsonRpcCall call = new JsonRpcCall(NullNode.instance, null, response);
        return new JsonRpcEnvelope(false, List.of(call));
    }

    private JsonRpcCall decodeCall(JsonNode item, RouteMount mount) {
        // 无效请求不能冒充通知：先验证信封，再按照 id 是否存在判断通知。
        JsonNode id = item.get("id");
        JsonNode params = item.get("params");
        String versionText = item.path("jsonrpc").textValue();
        boolean version = StringUtils.equals("2.0", versionText);
        boolean method = item.path("method").isTextual();
        boolean validParams = params == null || params.isArray() || params.isObject();
        if (!item.isObject() || !version || !method || !this.validId(id) || !validParams) {
            JsonNode response = error(NullNode.instance, -32600, "Invalid Request", null);
            return new JsonRpcCall(NullNode.instance, null, response);
        }

        try {
            RequestPayload request = this.resolve(item.get("method").textValue(), params, mount, id == null);
            return new JsonRpcCall(id, request, null);
        } catch (JsonRpcException failure) {
            JsonNode response = null;
            if (id != null) {
                response = error(id, failure.getCode(), failure.getMessage(), failure.getData());
            }
            return new JsonRpcCall(id, null, response);
        }
    }

    private boolean validId(JsonNode id) {
        return id == null || id.isNull() || id.isTextual() || id.isNumber();
    }

    private RequestPayload resolve(String name, JsonNode params, RouteMount mount, boolean notification) {
        if (name.startsWith("rpc.")) {
            throw new JsonRpcException(-32601, "Method not found", null);
        }
        int argumentCount = params == null ? 0 : params.size();
        String scopedService = this.protocol.option("service", null);
        MetBindInfo<?> selectedService = null;
        Method selectedMethod = null;
        boolean nameFound = false;

        // 只从当前挂载点允许公开的提供者中查找，忽略隐藏、订阅和静态方法。
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(serviceId);
            if (!this.visible(service, mount)) {
                continue;
            }
            String alias = alias(service);
            if (scopedService != null && !StringUtils.equals(scopedService, alias)) {
                continue;
            }
            for (Method candidate : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                if (candidate.isBridge() || candidate.isSynthetic()) {
                    continue;
                }
                String exposedName = alias + "." + candidate.getName();
                if (scopedService != null) {
                    exposedName = candidate.getName();
                }
                if (!StringUtils.equals(name, exposedName)) {
                    continue;
                }
                nameFound = true;
                if (candidate.getParameterCount() != argumentCount) {
                    continue;
                }
                if (selectedMethod != null) {
                    throw new JsonRpcException(-32602, "Invalid params", "Ambiguous overloaded method");
                }
                selectedService = service;
                selectedMethod = candidate;
            }
        }
        if (selectedMethod == null) {
            int code = nameFound ? -32602 : -32601;
            String message = nameFound ? "Invalid params" : "Method not found";
            throw new JsonRpcException(code, message, null);
        }

        // 入站由线协议 id 决定是否为通知；Java 消息注解只影响本地发起调用。
        return this.request(selectedService, selectedMethod, params, notification);
    }

    private boolean visible(MetBindInfo<?> service, RouteMount mount) {
        boolean provider = service != null && service.getServiceType() == MetServiceType.Provider;
        return provider && !service.isShadow() && this.protocol.accepts(service, mount);
    }

    private RequestPayload request(MetBindInfo<?> service, Method method, JsonNode params, boolean notification) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method.getName());
        request.setSerializeType("json");
        request.setMessage(notification);
        request.setReceiveTime(System.currentTimeMillis());
        request.setClientTimeout(this.context.getSettings().getDefaultTimeout());

        // 命名参数依赖接口编译时保留的参数名，不猜测 arg0 等位置名。
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            JsonNode value;
            if (params.isObject()) {
                if (!parameter.isNamePresent() || !params.has(parameter.getName())) {
                    throw new JsonRpcException(-32602, "Invalid params", "Named parameters require declared names");
                }
                value = params.get(parameter.getName());
            } else {
                value = params.get(index);
            }
            try {
                Object decoded = this.convert(value, parameter.getParameterizedType());
                request.addParameter(parameter.getType().getName(), decoded);
            } catch (IllegalArgumentException failure) {
                throw new JsonRpcException(-32602, "Invalid params", null);
            }
        }
        return request;
    }

    /** 发布检查仅约束本协议无法表达的调用形态。 */
    public static void validateService(ProtocolConfig protocol, MetBindInfo<?> service) {
        if (!protocol.accepts(service.getBindProtocols())) {
            return;
        }
        String alias = alias(service);
        boolean reservedAlias = StringUtils.equals(alias, "rpc") || StringUtils.startsWith(alias, "rpc.");
        if (StringUtils.isBlank(alias) || reservedAlias) {
            throw new IllegalArgumentException("JSON-RPC service alias must be nonblank and outside rpc. namespace");
        }
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            String unsupported = "JSON-RPC does not support streaming method: " + method.getName();
            if (Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException(unsupported);
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (Flow.Publisher.class.isAssignableFrom(parameter)) {
                    throw new IllegalArgumentException(unsupported);
                }
            }
        }
    }

    private static String alias(MetBindInfo<?> service) {
        String alias = service.getAliasName("jsonrpc");
        return StringUtils.isBlank(alias) ? service.getBindName() : alias;
    }

    public byte[] encodeRequest(RequestPayload request) throws IOException {
        MetBindInfo<?> service = this.service(request);
        String serviceAlias = alias(service);
        String name = serviceAlias + "." + request.getTargetMethod();
        String scopedService = this.protocol.option("service", null);
        if (scopedService != null) {
            if (!StringUtils.equals(scopedService, serviceAlias)) {
                throw new IOException("Service is not selected by this JSON-RPC endpoint");
            }
            name = request.getTargetMethod();
        }

        ObjectNode call = JSON.createObjectNode();
        call.put("jsonrpc", "2.0");
        call.put("method", name);
        call.set("params", JSON.valueToTree(request.getParameterValues()));
        if (!request.isMessage()) {
            call.put("id", request.getRequestID());
        }
        return this.write(call);
    }

    public String requestPath(RequestPayload request) {
        return this.protocol.contextPath(this.service(request));
    }

    public JsonNode encodeResponse(JsonRpcCall call, ResponsePayload response) {
        if (call.id() == null || response.getStatus() == ProtocolStatus.Accept) {
            return null;
        }
        if (response.getStatus() != ProtocolStatus.OK) {
            return this.encodeError(call, response.getFailure());
        }
        try {
            ObjectNode result = JSON.createObjectNode();
            result.put("jsonrpc", "2.0");
            result.set("id", call.id());
            result.set("result", JSON.valueToTree(response.getReturnData()));
            return result;
        } catch (IllegalArgumentException failure) {
            return error(call.id(), -32603, "Internal error", null);
        }
    }

    public JsonNode encodeError(JsonRpcCall call, Throwable failure) {
        if (call.id() == null) {
            return null;
        }
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException) {
            cause = cause.getCause();
        }
        if (cause instanceof JsonRpcException error) {
            try {
                return error(call.id(), error.getCode(), error.getMessage(), error.getData());
            } catch (IllegalArgumentException invalidData) {
                return error(call.id(), -32603, "Internal error", null);
            }
        }
        return error(call.id(), -32603, "Internal error", null);
    }

    private static JsonNode error(JsonNode id, int code, String message, Object data) {
        ObjectNode result = JSON.createObjectNode();
        result.put("jsonrpc", "2.0");
        result.set("id", id == null ? NullNode.instance : id);
        ObjectNode error = result.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", JSON.valueToTree(data));
        }
        return result;
    }

    /** 空列表表示整个批次只有通知，网络层不发送 JSON-RPC 响应。 */
    public byte[] encodeBatch(JsonRpcEnvelope envelope, List<JsonNode> responses) throws IOException {
        ArrayNode response = JSON.createArrayNode();
        for (JsonNode item : responses) {
            if (item != null) {
                response.add(item);
            }
        }
        if (response.isEmpty()) {
            return new byte[0];
        }
        if (envelope.batch()) {
            return this.write(response);
        } else {
            return this.write(response.get(0));
        }
    }

    public ResponsePayload decodeResponse(RequestPayload request, byte[] body) throws IOException {
        JsonNode response = this.readResponse(body);
        if (this.localRequestId(response.get("id")) != request.getRequestID()) {
            throw new IOException("JSON-RPC response id does not match the request");
        }
        if (response.has("error")) {
            JsonNode error = response.get("error");
            Object data = null;
            if (error.has("data")) {
                data = JSON.convertValue(error.get("data"), Object.class);
            }
            throw new JsonRpcException(error.get("code").intValue(), error.get("message").textValue(), data);
        }

        // 返回值按消费者接口的泛型声明恢复，不将 DTO 降级为无类型 Map。
        Method method = this.method(request);
        Object value = null;
        if (method.getReturnType() != void.class) {
            try {
                value = this.convert(response.get("result"), method.getGenericReturnType());
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid JSON-RPC result type", failure);
            }
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

    public long decodeReplyId(byte[] body) throws IOException {
        JsonNode id = this.readResponse(body).get("id");
        return this.localRequestId(id);
    }

    private long localRequestId(JsonNode id) throws IOException {
        if (id.isNumber()) {
            try {
                // 7 与 7.0 是同一个 JSON 数值，但字符串、非整数和越界数不能混用。
                return id.decimalValue().longValueExact();
            } catch (ArithmeticException failure) {
                throw new IOException("JSON-RPC response id is not an exact request id", failure);
            }
        }
        throw new IOException("JSON-RPC response id must match a numeric request id");
    }

    private JsonNode readResponse(byte[] body) throws IOException {
        if (body.length > this.maxMessageSize) {
            throw new IOException("JSON-RPC response exceeds maxMessageSize");
        }
        JsonNode response = JSON.readTree(body);
        if (response == null || !response.isObject()) {
            throw new IOException("Invalid JSON-RPC response");
        }
        boolean version = StringUtils.equals("2.0", response.path("jsonrpc").textValue());
        boolean oneResult = response.has("result") != response.has("error");
        JsonNode id = response.get("id");
        if (!version || !oneResult || id == null || !this.validId(id)) {
            throw new IOException("Invalid JSON-RPC response");
        }
        if (response.has("error")) {
            JsonNode error = response.get("error");
            JsonNode errorCode = error.path("code");
            boolean code = errorCode.canConvertToExactIntegral() && errorCode.canConvertToInt();
            if (!error.isObject() || !code || !error.path("message").isTextual()) {
                throw new IOException("Invalid JSON-RPC error response");
            }
        }
        return response;
    }

    private Object convert(JsonNode value, Type type) {
        JavaType target = JSON.getTypeFactory().constructType(type);
        return JSON.convertValue(value, target);
    }

    private Method method(RequestPayload request) throws IOException {
        Class<?>[] types = new Class<?>[request.getParameterTypes().size()];
        try {
            for (int index = 0; index < types.length; index++) {
                String parameterType = request.getParameterTypes().get(index);
                types[index] = MetRuntimeUtils.toJavaType(parameterType, this.context.getClassLoader());
            }
            Class<?> serviceType = this.service(request).getBindType();
            return MetRuntimeUtils.getServiceMethod(serviceType, request.getTargetMethod(), types);
        } catch (Exception failure) {
            throw new IOException("JSON-RPC consumer method is unavailable", failure);
        }
    }

    private MetBindInfo<?> service(RequestPayload request) {
        String group = request.getServiceGroup();
        String name = request.getServiceName();
        return this.context.getServiceInfo(group, name, request.getServiceVersion());
    }

    private byte[] write(JsonNode value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > this.maxMessageSize) {
            throw new IOException("JSON-RPC message exceeds maxMessageSize");
        }
        return bytes;
    }
}
