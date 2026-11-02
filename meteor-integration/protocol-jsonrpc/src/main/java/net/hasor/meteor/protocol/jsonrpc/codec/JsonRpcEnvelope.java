/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.util.List;

/** 完整 JSON 文档的解析结果，只有合法的非空数组才采用批量响应。 */
public final class JsonRpcEnvelope {
    private final boolean           batch;
    private final List<JsonRpcCall> calls;

    JsonRpcEnvelope(boolean batch, List<JsonRpcCall> calls) {
        this.batch = batch;
        this.calls = List.copyOf(calls);
    }

    public boolean batch() {
        return this.batch;
    }

    public List<JsonRpcCall> calls() {
        return this.calls;
    }
}
