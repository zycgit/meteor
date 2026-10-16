/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;

final class Http1InboundResponse {
    private final HttpResponse response;
    private final boolean      keepAlive;

    Http1InboundResponse(HttpResponse response, boolean keepAlive) {
        this.response = response;
        this.keepAlive = keepAlive;
    }

    HttpResponse response() {
        return this.response;
    }

    boolean keepAlive() {
        return this.keepAlive;
    }
}
