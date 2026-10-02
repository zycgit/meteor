/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.List;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;

/** Lifecycle and outbound logical session acquisition. */
public interface RsfConnector extends AutoCloseable {
    ConnectorConfig config();

    /** Initialize shared resources once, without listening or connecting to a peer. */
    void init() throws Exception;

    /** Disable further binds, fail pending binds and close all listeners. Existing connections remain usable.
     * Idempotent; call from the lifecycle thread before draining connections.
     */
    void closeBind();

    /** Close listeners, drain submitted writes, close connections and release resources. Call from the lifecycle thread. */
    void close();

    /** Set the synchronous close callback. The manager registers it before init().
     * Invoke once on close or initialization failure, after stopping admission and before releasing resources.
     * The callback must finish before resource release proceeds.
     */
    void onClosing(Consumer<RsfConnector> listener);

    /** Start the configured listener type at the supplied address after init(). Completes when the listener is usable.
     * Repeated binds of the same address share the operation; other bindings are independent.
     * The connector owns pending bind futures and must fail them when it closes.
     * Own physical listener resources as soon as they exist;
     * close resources rejected during shutdown. AbstractConnector tracks listeners and pending operations for its subclasses.
     */
    Future<RsfListen> bind(InterAddress address);

    /** First active listener address, or null when none is bound.
     * Use getListenList() to enumerate multiple addresses.
     */
    InterAddress getBindAddress();

    /** Immutable snapshot of listener handles owned by this connector, including pending binds.
     * Inspect isActive() before using a bound address; outbound-only connectors have an empty list.
     */
    List<RsfListen> getListenList();

    /** Create a new outgoing connection after init(), without requiring a local bind.
     * The provider registers both outgoing and accepted channels through the onChannelConnected callback.
     * Channel termination notifies the onChannelClosed callback; the manager owns unified cleanup.
     * The listener type is taken from this connector's configuration.
     */
    Future<RsfChannel> connect(InterAddress target);

    /** Set the synchronous channel registration callback before init().
     * Notify for accepted and outgoing channels before exposing them to callers or delivering messages.
     * The callback may close a channel rejected during shutdown.
     */
    void onChannelConnected(Consumer<RsfChannel> listener);

    /** Set the channel termination callback before init(), for both normal close and connection loss.
     * Notify with the terminated channel; its getConnector() identifies the owner.
     */
    void onChannelClosed(Consumer<RsfChannel> listener);
}
