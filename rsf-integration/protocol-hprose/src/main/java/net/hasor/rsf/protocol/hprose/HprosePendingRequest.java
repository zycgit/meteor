/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose;
import net.hasor.rsf.connector.transport.http.HttpExchange;

final class HprosePendingRequest {
    private final HttpExchange     exchange;
    private final HproseInvocation invocation;

    public HprosePendingRequest(HttpExchange exchange, HproseInvocation invocation) {
        this.exchange = exchange;
        this.invocation = invocation;
    }

    public HttpExchange exchange() {
        return this.exchange;
    }

    public HproseInvocation invocation() {
        return this.invocation;
    }
}
