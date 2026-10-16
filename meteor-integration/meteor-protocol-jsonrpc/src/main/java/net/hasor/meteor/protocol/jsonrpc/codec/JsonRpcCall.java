/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 一个已解析调用；缺少 id 的通知与显式 JSON null 保持区别。 */
public final class JsonRpcCall {
    private final JsonNode       id;
    private final RequestPayload request;
    private final JsonNode       response;

    JsonRpcCall(JsonNode id, RequestPayload request, JsonNode response) {
        this.id = id;
        this.request = request;
        this.response = response;
    }

    public JsonNode id() {
        return this.id;
    }

    public RequestPayload request() {
        return this.request;
    }

    public JsonNode response() {
        return this.response;
    }
}
