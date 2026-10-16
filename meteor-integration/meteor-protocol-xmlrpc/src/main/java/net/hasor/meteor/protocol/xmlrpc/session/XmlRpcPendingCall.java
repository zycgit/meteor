/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.RequestPayload;

/** One HTTP exchange remains owned by the session until its response write or RPC completes. */
final class XmlRpcPendingCall {
    private final RequestPayload    request;
    private final HttpExchange      exchange;
    private final BasicFuture<Void> sent = new BasicFuture<>();

    XmlRpcPendingCall(RequestPayload request, HttpExchange exchange) {
        this.request = request;
        this.exchange = exchange;
    }

    RequestPayload request() {
        return this.request;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }
}
