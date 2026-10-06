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

    public boolean markFailed() {
        if (this.failed) {
            return false;
        }

        this.failed = true;
        return true;
    }
}
