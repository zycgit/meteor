/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;

record TestHttpPendingRequest(HttpExchange exchange, TestHttpInvocation invocation) {
}
