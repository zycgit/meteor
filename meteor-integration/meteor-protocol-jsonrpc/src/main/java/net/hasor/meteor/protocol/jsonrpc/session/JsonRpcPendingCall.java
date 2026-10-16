/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.session;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcCall;

/** Associates one Dispatcher request with its original JSON-RPC batch position. */
final class JsonRpcPendingCall {
    private final JsonRpcResponseBatch batch;
    private final int                  index;

    JsonRpcPendingCall(JsonRpcResponseBatch batch, int index) {
        this.batch = batch;
        this.index = index;
    }

    JsonRpcResponseBatch batch() {
        return this.batch;
    }

    JsonRpcCall call() {
        return this.batch.envelope().calls().get(this.index);
    }

    int index() {
        return this.index;
    }
}
