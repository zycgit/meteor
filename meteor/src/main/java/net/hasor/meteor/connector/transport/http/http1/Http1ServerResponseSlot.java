/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;

/**
 * A response position in one HTTP/1 connection.
 */
final class Http1ServerResponseSlot {
    private final boolean           keepAlive;
    private final HttpExchange      exchange;
    private       HttpResponse      response;
    private       BasicFuture<Void> sent;
    private       boolean           continuePending;

    Http1ServerResponseSlot(boolean keepAlive, HttpExchange exchange, boolean continuePending) {
        this.keepAlive = keepAlive;
        this.exchange = exchange;
        this.continuePending = continuePending;
    }

    boolean takeContinue() {
        boolean pending = this.continuePending;
        this.continuePending = false;
        return pending;
    }

    boolean keepAlive() {
        return this.keepAlive;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    HttpResponse response() {
        return this.response;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }

    void prepareResponse(HttpResponse response, BasicFuture<Void> sent) {
        this.response = response;
        this.sent = sent;
    }
}
