/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc;
import java.util.Objects;

/** JSON-RPC 标准错误或应用错误，data 仅包含可编码为 JSON 的业务数据。 */
public final class JsonRpcException extends RuntimeException {
    private final int    code;
    private final Object data;

    public JsonRpcException(int code, String message, Object data) {
        super(Objects.requireNonNull(message, "message"));
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return this.code;
    }

    public Object getData() {
        return this.data;
    }
}
