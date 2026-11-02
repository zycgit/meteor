/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.RequestPayload;

/** One HTTP exchange remains owned by the session until its response write or RPC completes. */
final class HessianPendingCall {
    private final RequestPayload    request;
    private final HttpExchange      exchange;
    private final int               responseVersion;
    private final BasicFuture<Void> sent = new BasicFuture<>();

    HessianPendingCall(RequestPayload request, HttpExchange exchange, int responseVersion) {
        this.request = request;
        this.exchange = exchange;
        this.responseVersion = responseVersion;
    }

    RequestPayload request() {
        return this.request;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    int responseVersion() {
        return this.responseVersion;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }
}
