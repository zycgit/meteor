/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;

final class Http1InboundRequest {
    private final HttpRequest request;
    private final boolean     keepAlive;

    Http1InboundRequest(HttpRequest request, boolean keepAlive) {
        this.request = request;
        this.keepAlive = keepAlive;
    }

    HttpRequest request() {
        return this.request;
    }

    boolean keepAlive() {
        return this.keepAlive;
    }
}
