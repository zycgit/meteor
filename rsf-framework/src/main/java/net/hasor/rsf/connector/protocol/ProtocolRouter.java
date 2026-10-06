/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.ReceivedListener;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.connector.transport.NetworkRoute;
import net.hasor.rsf.connector.transport.RoutedReceiver;

/** Owns protocol sessions on one physical connection, independently of its transport. */
final class ProtocolRouter<M> implements RoutedReceiver<M> {
    private final EndpointConnector<M>              owner;
    private final NetworkChannel<M>                 network;
    private final ReceivedListener                  receiver;
    private final Map<String, ProtocolFactory<M>>   factories;
    private final Map<String, NetworkRoute<M>>      routes;
    private final BasicFuture<RsfChannel>           connecting;
    private final boolean                           shared;
    private final Runnable                          terminated;
    private final Map<String, ProtocolChannel<M>>   sessions = new LinkedHashMap<>();
    private final Map<String, SessionConnection<M>> views    = new LinkedHashMap<>();

    ProtocolRouter(EndpointConnector<M> owner, NetworkChannel<M> network, ReceivedListener receiver, Map<String, ProtocolFactory<M>> factories, Map<String, NetworkRoute<M>> routes, BasicFuture<RsfChannel> connecting, boolean shared, Runnable terminated) {
        this.owner = owner;
        this.network = network;
        this.receiver = receiver;
        this.factories = factories;
        this.routes = routes;
        this.connecting = connecting;
        this.shared = shared;
        this.terminated = terminated;
    }

    public Map<String, NetworkRoute<M>> routes() {
        return this.routes;
    }

    public String initialRoute() {
        return this.connecting == null ? null : this.network.getRemote().getSchema().toLowerCase(Locale.ROOT);
    }

    public void connected() {
        String initial = this.initialRoute();
        if (initial != null) {
            this.session(initial);
        } else if (this.factories.size() == 1) {
            this.session(this.factories.keySet().iterator().next());
        }
    }

    private ProtocolChannel<M> session(String route) {
        ProtocolChannel<M> session = this.sessions.get(route);
        if (session == null) {
            ProtocolConfig configured = this.owner.config().protocol(route);
            ProtocolConfig effective = new ProtocolConfig(configured.name(), route, configured.protocol(), this.routes.get(route).options());
            SessionConnection<M> view = new SessionConnection<>(this.network, route, this.shared && this.connecting == null);
            session = new ProtocolChannel<>(this.owner, this.owner.manager().nextConnectionId(), this.receiver, view::execute, view, this.factories.get(route), this.connecting, effective);
            view.onClose(session::closed);
            this.views.put(route, view);
            this.sessions.put(route, session);
            session.connected();
        }
        return session;
    }

    public void receive(String route, M message) throws Exception {
        this.session(route).receive(message);
    }

    public void receive(M message) {
        throw new IllegalStateException("Protocol route is missing");
    }

    public void closed(Throwable failure) {
        Throwable cause = failure == null ? new IOException("Connection closed") : failure;
        try {
            for (SessionConnection<M> view : this.views.values()) {
                view.terminated(cause);
            }
        } finally {
            try {
                if (this.connecting != null) {
                    this.connecting.failed(cause);
                }
            } finally {
                this.terminated.run();
            }
        }
    }
}
