/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import net.hasor.cobble.concurrent.future.BasicFuture;

class HttpCall {
    private final HttpExchange      request;
    private final BasicFuture<Void> sent;
    private       boolean           failed;
    private       HttpResponse      response;
    private       boolean           keepAlive;

    public HttpCall(HttpExchange request, BasicFuture<Void> sent) {
        this.request = request;
        this.sent = sent;
    }

    public HttpExchange request() {
        return this.request;
    }

    public BasicFuture<Void> sent() {
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

    public boolean markFailed() {
        if (this.failed) {
            return false;
        }

        this.failed = true;
        return true;
    }
}
