/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc;
import java.util.Objects;

/** XML-RPC fault 的错误码和描述，由业务或协议处理提供。 */
public final class XmlRpcException extends RuntimeException {
    private final int code;

    public XmlRpcException(int code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }
}
