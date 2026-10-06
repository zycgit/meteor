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

/** An outbound or accepted protocol channel; transport implementations own response correlation. */
public interface MetChannel {
    /** The connector that owns this channel for its entire lifetime. */
    MetConnector getConnector();

    /** Connection identity assigned by ConnectorManager, independent of request IDs and remote addresses. */
    long getChannelId();

    /** Local endpoint with protocol scheme, or null while no physical connection is established. */
    InterAddress getLocal();

    /** Remote endpoint with protocol scheme. */
    InterAddress getRemote();

    boolean isActive();

    /**
     * Sends a request or response and completes with this channel after writing, independently of any RPC response.
     * A response ID identifies a pending inbound request on this channel.
     * Unsupported payloads/directions and unknown/finished response IDs fail the future.
     * Failure notification payloads are local events and cannot be sent.
     */
    Future<MetChannel> sendData(Payload payload);

    /** Reject new sends, drain submitted writes, then complete with this channel after closure. */
    Future<MetChannel> drainAndClose();

    /** Close without draining writes; complete with this channel when closure finishes. */
    Future<MetChannel> close();
}
