/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import net.hasor.meteor.connector.transport.http.HttpExchange;

final class TestHttpPendingRequest {
    private final HttpExchange       exchange;
    private final TestHttpInvocation invocation;

    public TestHttpPendingRequest(HttpExchange exchange, TestHttpInvocation invocation) {
        this.exchange = exchange;
        this.invocation = invocation;
    }

    public HttpExchange exchange() {
        return this.exchange;
    }

    public TestHttpInvocation invocation() {
        return this.invocation;
    }
}
