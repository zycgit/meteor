/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.http;
import net.hasor.cobble.concurrent.future.BasicFuture;

/**
 * A response position in one HTTP/1 connection.
 */
final class HttpReply {
    private final boolean           keepAlive;
    private final HttpExchange      exchange;
    private       HttpResponse      response;
    private       BasicFuture<Void> sent;

    public HttpReply(boolean keepAlive, HttpExchange exchange) {
        this.keepAlive = keepAlive;
        this.exchange = exchange;
    }

    public boolean keepAlive() {
        return this.keepAlive;
    }

    public HttpExchange exchange() {
        return this.exchange;
    }

    public HttpResponse response() {
        return this.response;
    }

    public BasicFuture<Void> sent() {
        return this.sent;
    }

    public void prepareResponse(HttpResponse response, BasicFuture<Void> sent) {
        this.response = response;
        this.sent = sent;
    }
}
