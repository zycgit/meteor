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
record JsonRpcPendingCall(JsonRpcResponseBatch batch, int index) {

    JsonRpcCall call() {
        return this.batch.envelope().calls().get(this.index);
    }
}
