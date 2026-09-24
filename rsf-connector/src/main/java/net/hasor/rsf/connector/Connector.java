/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.List;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;

/** Lifecycle and outbound logical session acquisition. */
public interface Connector extends AutoCloseable {
    ConnectorConfig config();

    /** Initialize shared resources once, without listening or connecting to a peer. */
    void init() throws Exception;

    void close();

    /** Immutable snapshot of bound listener handles; outbound-only connectors have an empty list.
     * Handles remain owned until connector close; inspect isActive() for current availability.
     */
    List<RsfListen> getListenList();

    /** Start the requested listener type at the supplied address after init(). Completes when the listener is usable.
     * Repeated binds of the same type and address share the operation; other bindings are independent.
     */
    Future<RsfListen> bind(String listenType, InterAddress address);

    /** First active address of the requested listener type, or null when none is bound.
     * Use getListenList() to enumerate multiple addresses of the same type.
     */
    InterAddress getBindAddress(String listenType);

    /** Create a new outgoing connection after init(), without requiring a local bind.
     * The caller owns the returned connection. Use ConnectorManager.connect for managed reuse and cleanup.
     * Unsupported listener types fail the returned future.
     */
    Future<RsfChannel> connect(String listenType, InterAddress target);
}
