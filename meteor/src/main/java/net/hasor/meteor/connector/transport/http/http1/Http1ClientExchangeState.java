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

final class Http1ClientExchangeState {
    private final HttpExchange      exchange;
    private final BasicFuture<Void> sent;
    private       boolean           failed;
    private       HttpResponse      response;
    private       boolean           keepAlive;

    Http1ClientExchangeState(HttpExchange exchange, BasicFuture<Void> sent) {
        this.exchange = exchange;
        this.sent = sent;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }

    void response(HttpResponse response, boolean keepAlive) {
        this.response = response;
        this.keepAlive = keepAlive;
    }

    HttpResponse response() {
        return this.response;
    }

    boolean keepAlive() {
        return this.keepAlive;
    }

    boolean markFailed() {
        if (this.failed) {
            return false;
        }

        this.failed = true;
        return true;
    }
}
