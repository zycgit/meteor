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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcResponseTest {
    private MetContext   context;
    private JsonRpcCodec codec;

    @Before
    public void prepareConsumerMetadata() throws Exception {
        this.context = new MetConfiguration().setDefaultGroup("wire").buildContext();
        this.context.publisher().rsfService(JsonRpcBindingTest.Methods.class) //
                .name("Methods").aliasName("jsonrpc", "math").register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of("contextPath", "/rpc"));
        this.codec = new JsonRpcCodec(protocol, this.context);
    }

    @After
    public void closeContext() throws Exception {
        this.context.close();
    }

    @Test
    public void clientRequestEncodesNumericIdAndQualifiedMethod() throws Exception {
        // 出站调用采用本地请求 ID，并附带服务别名。
        RequestPayload request = this.request("ping");
        JsonNode encoded = this.json(this.codec.encodeRequest(request));
        assertEquals("math.ping", encoded.path("method").textValue());
        assertEquals(7, encoded.path("id").longValue());
        assertTrue(encoded.path("params").isArray());
    }

    @Test
    public void clientNotificationOmitsId() throws Exception {
        // 消息调用不发送 id，不能使用 null 替代字段缺失。
        RequestPayload request = this.request("ping");
        request.setMessage(true);
        JsonNode encoded = this.json(this.codec.encodeRequest(request));
        assertFalse(encoded.has("id"));
    }

    @Test
    public void clientPathComesFromConsumerContextSelection() {
        // 出站目录使用消费者的协议配置。
        assertEquals("/rpc", this.codec.requestPath(this.request("ping")));
    }

    @Test
    public void scopedClientOmitsServicePrefix() throws Exception {
        // 同样的 service 选项同时约束入站和出站命名。
        ProtocolConfig scoped = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", //
                Map.of("contextPath", "/rpc", "service", "math"));
        JsonRpcCodec scopedCodec = new JsonRpcCodec(scoped, this.context);
        JsonNode encoded = this.json(scopedCodec.encodeRequest(this.request("ping")));
        assertEquals("ping", encoded.path("method").textValue());
    }

    @Test
    public void mismatchedScopedServiceFailsBeforeSending() {
        // 指定其他服务的端点不能用于发送当前消费者请求。
        ProtocolConfig scoped = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", //
                Map.of("contextPath", "/rpc", "service", "other"));
        JsonRpcCodec scopedCodec = new JsonRpcCodec(scoped, this.context);
        assertThrows(IOException.class, () -> scopedCodec.encodeRequest(this.request("ping")));
    }

    @Test
    public void responseKeepsOriginalStringId() {
        // 服务端回应使用外部 ID，独立于 Dispatcher 的 long ID。
        JsonRpcCall call = new JsonRpcCall(new TextNode("0007"), null, null);
        JsonNode response = this.codec.encodeResponse(call, this.response("pong"));
        assertEquals("0007", response.path("id").textValue());
        assertEquals("pong", response.path("result").textValue());
        assertFalse(response.has("error"));
    }

    @Test
    public void nullResultIsPresentRatherThanOmitted() {
        // void 或空结果仍需要显式 result 字段。
        JsonRpcCall call = new JsonRpcCall(NullNode.instance, null, null);
        JsonNode response = this.codec.encodeResponse(call, this.response(null));
        assertTrue(response.has("result"));
        assertTrue(response.get("result").isNull());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void notificationSuppressesSuccessfulResponse() {
        // 无 ID 调用无论执行结果如何都不能产生响应。
        JsonRpcCall call = new JsonRpcCall(null, null, null);
        assertNull(this.codec.encodeResponse(call, this.response("pong")));
    }

    @Test
    public void notificationSuppressesFailureResponse() {
        // 通知发生业务异常时不回送错误对象。
        JsonRpcCall call = new JsonRpcCall(null, null, null);
        assertNull(this.codec.encodeError(call, new IllegalStateException("failure")));
    }

    @Test
    public void ordinaryAcceptIsNotABusinessResult() {
        // Dispatcher 接纳回执属于本地生命周期，不映射成提前返回的 result。
        JsonRpcCall call = new JsonRpcCall(new TextNode("id"), null, null);
        ResponsePayload response = this.response(null);
        response.setStatus(ProtocolStatus.Accept);
        assertNull(this.codec.encodeResponse(call, response));
    }

    @Test
    public void applicationErrorPreservesCodeMessageAndData() {
        // 应用显式抛出的错误保留独立代码和业务数据。
        JsonRpcCall call = new JsonRpcCall(new TextNode("id"), null, null);
        JsonRpcException failure = new JsonRpcException(1234, "Rejected", Map.of("field", "name"));
        JsonNode result = this.codec.encodeError(call, new InvocationTargetException(failure));
        assertEquals(1234, result.path("error").path("code").intValue());
        assertEquals("Rejected", result.path("error").path("message").textValue());
        assertEquals("name", result.path("error").path("data").path("field").textValue());
        assertFalse(result.has("result"));
    }

    @Test
    public void unclassifiedFailureReturnsInternalErrorWithoutStackTrace() {
        // 普通业务异常映射为标准内部错误，不把实现细节编码到对端。
        JsonRpcCall call = new JsonRpcCall(new TextNode("id"), null, null);
        JsonNode result = this.codec.encodeError(call, new IllegalStateException("private details"));
        assertEquals(-32603, result.path("error").path("code").intValue());
        assertEquals("Internal error", result.path("error").path("message").textValue());
        assertFalse(result.path("error").has("data"));
    }

    @Test
    public void failedResponseUsesItsOriginalException() {
        // Dispatcher 附带的异常优先提供应用错误信息。
        JsonRpcCall call = new JsonRpcCall(new TextNode("id"), null, null);
        ResponsePayload response = this.response(null);
        response.setStatus(ProtocolStatus.InvokeError);
        response.setFailure(new JsonRpcException(42, "Business rejection", null));
        JsonNode encoded = this.codec.encodeResponse(call, response);
        assertEquals(42, encoded.path("error").path("code").intValue());
    }

    @Test
    public void batchOmitsNotificationPositions() throws Exception {
        // 批次仅汇集有响应的调用，不用 null 填充通知位置。
        JsonRpcEnvelope envelope = new JsonRpcEnvelope(true, List.of());
        JsonRpcCall call = new JsonRpcCall(new TextNode("id"), null, null);
        JsonNode error = this.codec.encodeError(call, new JsonRpcException(-32601, "Method not found", null));
        JsonNode result = this.json(this.codec.encodeBatch(envelope, Arrays.asList(null, error)));
        assertTrue(result.isArray());
        assertEquals(1, result.size());
        assertEquals("id", result.get(0).path("id").textValue());
    }

    @Test
    public void notificationOnlyBatchProducesNoDocument() throws Exception {
        // 全通知批次返回零字节，不能生成空 JSON 数组。
        JsonRpcEnvelope envelope = new JsonRpcEnvelope(true, List.of());
        assertEquals(0, this.codec.encodeBatch(envelope, Arrays.asList(null, null)).length);
    }

    @Test
    public void scalarEnvelopeProducesAScalarResponse() throws Exception {
        // 单调用错误使用对象，不能额外套一层数组。
        JsonRpcEnvelope envelope = new JsonRpcEnvelope(false, List.of());
        JsonRpcCall call = new JsonRpcCall(NullNode.instance, null, null);
        JsonNode error = this.codec.encodeError(call, new JsonRpcException(-32600, "Invalid Request", null));
        JsonNode result = this.json(this.codec.encodeBatch(envelope, List.of(error)));
        assertTrue(result.isObject());
    }

    @Test
    public void consumerResultUsesDeclaredGenericDtoType() throws Exception {
        // 结果中的 DTO 集合使用消费者接口的泛型类型。
        RequestPayload request = this.request("people");
        request.addParameter(List.class.getName(), List.of());
        byte[] wire = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":[{\"name\":\"Ada\"}]}");
        ResponsePayload response = this.codec.decodeResponse(request, wire);
        List<?> result = (List<?>) response.getReturnData();
        assertTrue(result.get(0) instanceof JsonRpcBindingTest.Person);
        assertEquals("Ada", ((JsonRpcBindingTest.Person) result.get(0)).getName());
    }

    @Test
    public void consumerErrorRetainsStructuredData() {
        // 远端应用错误直接传递 JSON-RPC 错误对象，不转换成普通结果。
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{";
        body += "\"code\":-32042,\"message\":\"Rejected\",\"data\":{\"retry\":false}}}";
        byte[] wire = this.bytes(body);
        JsonRpcException error = assertThrows(JsonRpcException.class, //
                () -> this.codec.decodeResponse(this.request("ping"), wire));
        assertEquals(-32042, error.getCode());
        assertEquals("Rejected", error.getMessage());
        assertEquals(Map.of("retry", false), error.getData());
    }

    @Test
    public void websocketReplyLookupUsesExactIntegralId() throws Exception {
        // WebSocket 关联表只接受本端实际发送的 long 数字 ID。
        byte[] reply = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807,\"result\":null}");
        assertEquals(Long.MAX_VALUE, this.codec.decodeReplyId(reply));
    }

    @Test
    public void equivalentDecimalResponseIdMatchesNumericRequest() throws Exception {
        // JSON 数字不区分整数字面量和等值十进制写法。
        byte[] reply = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7.0,\"result\":\"pong\"}");
        ResponsePayload response = this.codec.decodeResponse(this.request("ping"), reply);
        assertEquals("pong", response.getReturnData());
    }

    @Test
    public void websocketReplyLookupAcceptsAnEquivalentDecimalId() throws Exception {
        // 关联查询和完整响应解码采用相同的精确数值规则。
        byte[] reply = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7.00,\"result\":null}");
        assertEquals(7, this.codec.decodeReplyId(reply));
    }

    @Test
    public void applicationErrorRequiresAStringMessage() {
        // 应用异常创建时保证协议要求的错误消息不是 JSON null。
        assertThrows(NullPointerException.class, () -> new JsonRpcException(42, null, null));
    }

    @Test
    public void encodedMessageLimitAppliesBeforeNetworkWrite() {
        // 出站大小限制覆盖完整信封。
        ProtocolConfig limited = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of("maxMessageSize", "4"));
        JsonRpcCodec codec = new JsonRpcCodec(limited, this.context);
        assertThrows(IOException.class, () -> codec.encodeRequest(this.request("ping")));
    }

    @Test
    public void mismatchedResponseIdIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void stringResponseIdCannotAliasNumericRequestId() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void fractionalResponseIdCannotAliasIntegralRequestId() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7.5,\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void overflowResponseIdCannotAliasLong() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":18446744073709551623,\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void nullResponseIdCannotMatchClientRequest() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void missingResponseIdIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void responseWithResultAndErrorIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null,";
        byte[] body = this.bytes(envelope + "\"error\":{\"code\":1,\"message\":\"bad\"}}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void responseWithoutResultOrErrorIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void wrongResponseVersionIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"1.0\",\"id\":7,\"result\":null}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void nonIntegerErrorCodeIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":1.5,\"message\":\"bad\"}}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void integralDecimalErrorCodeIsAccepted() {
        // 错误码要求整数值，十进制写法不改变数值的整数性质。
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":";
        byte[] body = this.bytes(envelope + "{\"code\":-32601.0,\"message\":\"Method not found\"}}");

        // 保留对端的标准错误，而不是把合法响应改成协议解析失败。
        JsonRpcException error = assertThrows(JsonRpcException.class, //
                () -> this.codec.decodeResponse(this.request("ping"), body));
        assertEquals(-32601, error.getCode());
        assertEquals("Method not found", error.getMessage());
    }

    @Test
    public void integralExponentErrorCodeIsAccepted() {
        // 指数表示同样按照精确数值判断，不依赖 JSON 词法类型。
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":";
        byte[] body = this.bytes(envelope + "{\"code\":-3.2601e4,\"message\":\"Method not found\"}}");
        JsonRpcException error = assertThrows(JsonRpcException.class, //
                () -> this.codec.decodeResponse(this.request("ping"), body));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void overflowingErrorCodeIsRejected() {
        // 超出错误码类型容量的整数不能截断成另一个错误。
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":";
        byte[] body = this.bytes(envelope + "{\"code\":2147483648.0,\"message\":\"Overflow\"}}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void nonStringErrorMessageIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":1,\"message\":1}}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void scalarErrorIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":1}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void arrayResponseIsRejectedForScalarClientRequest() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("[{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null}]");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void emptyResponseIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void trailingResponseIsRejected() {
        // 该响应违反单条调用的信封或关联契约。
        byte[] body = this.bytes("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null} {}");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    private RequestPayload request(String method) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(7);
        request.setServiceGroup("wire");
        request.setServiceName("Methods");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod(method);
        return request;
    }

    private ResponsePayload response(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private JsonNode json(byte[] bytes) throws IOException {
        return new ObjectMapper().readTree(bytes);
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
