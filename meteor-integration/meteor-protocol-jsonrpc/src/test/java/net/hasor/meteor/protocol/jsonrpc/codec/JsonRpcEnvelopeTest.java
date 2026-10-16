/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcEnvelopeTest {
    @Test
    public void malformedJsonReturnsParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void emptyBodyReturnsParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void blankBodyReturnsParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "  \n";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void trailingDocumentReturnsParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{} {}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void duplicateFieldReturnsParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"1.0\",\"method\":\"echo\"}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void scalarDocumentReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "42";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void nullDocumentReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "null";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void missingVersionReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"method\":\"echo\",\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void wrongVersionReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"1.0\",\"method\":\"echo\",\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void numericVersionReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":2.0,\"method\":\"echo\",\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void missingMethodReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void nonTextualMethodReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":1,\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void booleanIdReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":true}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void objectIdReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":{}}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void arrayIdReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":[]}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void scalarParamsReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":1,\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void nullParamsReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":null,\"id\":1}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void malformedNotificationStillReturnsInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "{\"jsonrpc\":\"2.0\",\"method\":true}";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void emptyBatchReturnsSingleInvalidRequest() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "[]";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32600, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void malformedBatchReturnsSingleParseError() {
        // 无效文档在服务解析之前失败，错误 ID 为 null。
        String body = "[{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1},";
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes(body), null);

        // 整体错误必须是单条响应，不能伪装成空批次或通知。
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        JsonNode response = envelope.calls().get(0).response();
        assertEquals(-32700, response.path("error").path("code").intValue());
        assertTrue(response.get("id").isNull());
    }

    @Test
    public void eachInvalidBatchEntryKeepsItsOwnError() {
        // 三个无效元素属于同一个合法非空批次。
        JsonRpcEnvelope envelope = this.codec(Map.of()).decodeRequest(this.bytes("[1,null,{}]"), null);

        // 批次保留三个响应位置，每一项都是无效请求。
        assertTrue(envelope.batch());
        assertEquals(3, envelope.calls().size());
        for (JsonRpcCall call : envelope.calls()) {
            assertEquals(-32600, call.response().path("error").path("code").intValue());
        }
    }

    @Test
    public void oversizedMessageIsRejectedBeforeParsing() {
        // 正文大小限制在 JSON 解析之前执行。
        JsonRpcCodec codec = this.codec(Map.of("maxMessageSize", "3"));
        JsonRpcEnvelope envelope = codec.decodeRequest(this.bytes("[1,2]"), null);
        assertFalse(envelope.batch());
        assertEquals(-32600, envelope.calls().get(0).response().path("error").path("code").intValue());
    }

    @Test
    public void oversizedBatchIsRejectedAsAWhole() {
        // 批次数量超限时不能提交前一部分调用。
        JsonRpcCodec codec = this.codec(Map.of("maxBatchCalls", "1"));
        JsonRpcEnvelope envelope = codec.decodeRequest(this.bytes("[1,2]"), null);
        assertFalse(envelope.batch());
        assertEquals(1, envelope.calls().size());
        assertEquals(-32600, envelope.calls().get(0).response().path("error").path("code").intValue());
    }

    private JsonRpcCodec codec(Map<String, String> options) {
        ProtocolConfig protocol = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", options);
        return new JsonRpcCodec(protocol, null);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
