/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.thrift.codec.ThriftMessage;

/** Correlation owned by one HTTP exchange, including its original Thrift envelope. */
final class ThriftHttpPendingCall {
    private final RequestPayload    request;
    private final HttpExchange      exchange;
    private final ThriftMessage     original;
    private final BasicFuture<Void> sent = new BasicFuture<>();

    ThriftHttpPendingCall(RequestPayload request, HttpExchange exchange, ThriftMessage original) {
        this.request = request;
        this.exchange = exchange;
        this.original = original;
    }

    RequestPayload request() {
        return this.request;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    ThriftMessage original() {
        return this.original;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }
}
