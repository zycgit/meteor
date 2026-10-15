/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.payload.Payload;

/** Minimal protocol channel used by the RPC layer. */
public interface MetChannel {
    /** Public protocol identity selected for this channel. */
    String getProtocol();

    /** Local endpoint with protocol scheme, or null while no physical connection is established. */
    InterAddress getLocal();

    /** Remote endpoint with protocol scheme. */
    InterAddress getRemote();

    /**
     * Sends a request or response and completes with this channel after writing, independently of any RPC response.
     * A response ID identifies a pending inbound request on this channel.
     * Unsupported payloads/directions and unknown/finished response IDs fail the future.
     * Failure notification payloads are local events and cannot be sent.
     */
    Future<MetChannel> sendData(Payload payload);
}
