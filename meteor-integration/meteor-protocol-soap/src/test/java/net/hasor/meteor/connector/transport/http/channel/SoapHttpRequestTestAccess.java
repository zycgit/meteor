/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import net.hasor.meteor.connector.RouteSelection;

/** Thin test access to the transport-owned route attachment. */
public final class SoapHttpRequestTestAccess {
    private SoapHttpRequestTestAccess() {
    }

    public static HttpRequest route(HttpRequest request, RouteSelection selection) {
        request.route(selection);
        return request;
    }
}
