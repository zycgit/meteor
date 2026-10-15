/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;

/** A selected route travels with its message, independently of subsequent routing decisions. */
public final class RouteMessage<M> {
    private final String mountId;
    private final M      message;

    public RouteMessage(String mountId, M message) {
        this.mountId = mountId;
        this.message = message;
    }

    public String mountId() {
        return this.mountId;
    }

    public M message() {
        return this.message;
    }
}
