/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;

/** A selected protocol name travels with its message within the owning connector endpoint. */
public final class RouteMessage<M> {
    private final String protocolName;
    private final M      message;

    public RouteMessage(String protocolName, M message) {
        this.protocolName = protocolName;
        this.message = message;
    }

    public String protocolName() {
        return this.protocolName;
    }

    public M message() {
        return this.message;
    }
}
