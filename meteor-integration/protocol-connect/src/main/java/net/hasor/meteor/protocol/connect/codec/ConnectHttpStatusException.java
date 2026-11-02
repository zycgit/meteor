/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;

/** 尚未采用 Connect 信封的 HTTP 方法或媒体协商错误。 */
final class ConnectHttpStatusException extends MetException {
    private static final long serialVersionUID = 1L;
    private final        int  httpStatus;

    ConnectHttpStatusException(int httpStatus, String message) {
        super(ProtocolStatus.ProtocolError, message);
        this.httpStatus = httpStatus;
    }

    int getHttpStatus() {
        return this.httpStatus;
    }
}
