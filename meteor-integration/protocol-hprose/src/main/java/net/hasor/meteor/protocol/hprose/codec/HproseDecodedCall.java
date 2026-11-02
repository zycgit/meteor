/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 一个批量报文中的调用，解析失败时仍保留对应的响应位置。 */
public final class HproseDecodedCall {
    private final RequestPayload request;
    private final boolean        byReference;
    private final Throwable      failure;

    HproseDecodedCall(RequestPayload request, boolean byReference, Throwable failure) {
        this.request = request;
        this.byReference = byReference;
        this.failure = failure;
    }

    public RequestPayload request() {
        return this.request;
    }

    public boolean byReference() {
        return this.byReference;
    }

    public Throwable failure() {
        return this.failure;
    }
}
