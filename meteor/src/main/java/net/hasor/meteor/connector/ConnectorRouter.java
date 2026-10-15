/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.connector.transport.ContextRoute;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkRoute;
import net.hasor.meteor.connector.transport.RoutedReceiver;

/** Owns protocol sessions on one physical connection, independently of its transport. */
final class ConnectorRouter<M> implements RoutedReceiver<M> {
    private static final Logger                            logger   = Logger.getLogger(ConnectorRouter.class);
    private final        ConnectorEndpoint<M>              owner;
    private final        NetworkChannel<M>                 network;
    private final        Map<String, ProtocolMount<M>>     mounts;
    private final        Map<String, NetworkRoute<M>>      routes;
    private final        BasicFuture<MetChannel>           connecting;
    private final        String                            initialMountId;
    private final        boolean                           inbound;
    private final        boolean                           shared;
    private final        Runnable                          terminated;
    private final        Map<String, ConnectorChannel<M>>  sessions = new LinkedHashMap<>();
    private final        Map<String, SessionConnection<M>> views    = new LinkedHashMap<>();

    ConnectorRouter(ConnectorEndpoint<M> owner, NetworkChannel<M> network, //
            Map<String, ProtocolMount<M>> mounts, //
            Map<String, NetworkRoute<M>> routes, //
            BasicFuture<MetChannel> connecting, String initialMountId, //
            boolean inbound, boolean shared, Runnable terminated) {
        this.owner = owner;
        this.network = network;
        this.mounts = mounts;
        this.routes = routes;
        this.connecting = connecting;
        this.initialMountId = initialMountId;
        this.inbound = inbound;
        this.shared = shared;
        this.terminated = terminated;
    }

    @Override
    public Map<String, NetworkRoute<M>> routes() {
        return this.routes;
    }

    @Override
    public Function<String, ContextRoute> contextRouter() {
        ContextRoutes routes = this.owner.contextRoutes();
        return routes == null ? uri -> null : routes::match;
    }

    @Override
    public String initialMountId() {
        return this.initialMountId;
    }

    @Override
    public void connected() {
        if (this.inbound && !this.owner.acceptingInbound(this.network)) {
            this.owner.closeInbound(this.network);
            return;
        }

        String initial = this.initialMountId();
        if (initial != null) {
            this.session(initial);
        } else if (this.mounts.size() == 1) {
            this.session(this.mounts.keySet().iterator().next());
        }
    }

    private ConnectorChannel<M> session(String mountId) {
        ConnectorChannel<M> session = this.sessions.get(mountId);
        if (session == null) {
            ProtocolMount<M> mount = this.mounts.get(mountId);
            if (mount == null) {
                throw new IllegalArgumentException("Unknown protocol mount: " + mountId);
            }

            boolean inboundPartition = this.shared && this.connecting == null;
            String scheme = mount.config().scheme();
            SessionConnection<M> view = new SessionConnection<>(this.network, scheme, inboundPartition);
            long channelId = this.owner.manager().nextConnectionId();
            Executor executor = view::execute;

            session = new ConnectorChannel<>(this.owner, channelId, executor, view, mount.factory(), this.connecting, mount.config());
            view.onClose(session::closed);
            this.views.put(mountId, view);
            this.sessions.put(mountId, session);
            session.connected();
        }
        return session;
    }

    @Override
    public void receive(String mountId, M message) throws Exception {
        if (this.inbound && !this.owner.acceptingInbound(this.network)) {
            this.owner.closeInbound(this.network);
            return;
        }
        this.session(mountId).receive(message);
    }

    @Override
    public void receive(M message) {
        throw new IllegalStateException("Protocol route is missing");
    }

    @Override
    public void closed(Throwable failure) {
        Throwable cause = failure == null ? new IOException("Connection closed") : failure;
        for (SessionConnection<M> view : this.views.values()) {
            try {
                view.terminated(cause);
            } catch (RuntimeException | Error cleanup) {
                logger.warn("Protocol session close failed", cleanup);
            }
        }

        if (this.connecting != null) {
            try {
                this.connecting.failed(cause);
            } catch (RuntimeException | Error cleanup) {
                logger.warn("Protocol connection failure notification failed", cleanup);
            }
        }

        this.terminated.run();
    }
}
