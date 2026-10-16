/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.session;
import net.hasor.meteor.protocol.hprose.codec.HproseDecodedCall;

/** 等待业务响应的调用及其在 HTTP 批次中的位置。 */
final class HprosePendingCall {
    private final HproseResponseBatch batch;
    private final HproseDecodedCall   call;
    private final int                 index;

    HprosePendingCall(HproseResponseBatch batch, HproseDecodedCall call, int index) {
        this.batch = batch;
        this.call = call;
        this.index = index;
    }

    HproseResponseBatch batch() {
        return this.batch;
    }

    HproseDecodedCall call() {
        return this.call;
    }

    int index() {
        return this.index;
    }
}
