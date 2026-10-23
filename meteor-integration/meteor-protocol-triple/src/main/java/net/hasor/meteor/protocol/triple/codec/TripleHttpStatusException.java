/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;

/** HTTP 方法和媒体协商失败保留 HTTP 自身的状态。 */
final class TripleHttpStatusException extends MetException {
    private final int httpStatus;

    TripleHttpStatusException(int httpStatus, String message) {
        super(ProtocolStatus.ProtocolError, message);
        this.httpStatus = httpStatus;
    }

    int getHttpStatus() {
        return this.httpStatus;
    }
}
