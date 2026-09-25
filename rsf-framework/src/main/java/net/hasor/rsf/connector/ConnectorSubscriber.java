/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import net.hasor.rsf.domain.payload.Payload;

/** Application subscription to a message from a specific channel. */
@FunctionalInterface
public interface ConnectorSubscriber {
    void onMessage(RsfChannel channel, long requestId, Payload payload);
}
