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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteReceiver;

/** Owns protocol sessions on one physical connection. Lifecycle callbacks follow channel order. */
final class RouteChannelDispatcher<M> implements RouteReceiver<M> {
    private static final Logger logger = Logger.getLogger(RouteChannelDispatcher.class);
    private final        ConnectorEndpoint<M>                 endpoint;
    private final        NetworkChannel<M>                    networkChannel;
    private final        Map<String, ProtocolMountBinding<M>> bindings;
    private final        BasicFuture<MetChannel>              outboundResult;
    private final        String                               initialMountId;
    private final        boolean                              sharedRoutes;
    private final        Runnable                             terminationListener;
    private final        Map<String, ConnectorChannel<M>>     protocolChannels = new LinkedHashMap<>();
    private              LifecycleState                       lifecycle        = LifecycleState.NEW;

    private enum LifecycleState {
        NEW,
        ACTIVE,
        CLOSED
    }

    RouteChannelDispatcher(ConnectorEndpoint<M> endpoint, NetworkChannel<M> networkChannel, //
            Map<String, ProtocolMountBinding<M>> bindings, //
            BasicFuture<MetChannel> outboundResult, String initialMountId, //
            boolean sharedRoutes, Runnable terminationListener) {
        this.endpoint = endpoint;
        this.networkChannel = networkChannel;
        this.bindings = bindings;
        this.outboundResult = outboundResult;
        this.initialMountId = initialMountId;
        this.sharedRoutes = sharedRoutes;
        this.terminationListener = terminationListener;
    }

    @Override
    public void connected() {
        if (this.lifecycle != LifecycleState.NEW) {
            return;
        }

        boolean accepted = this.endpoint.registerConnection(this.networkChannel, this.isInbound());
        if (!accepted) {
            IllegalStateException failure = new IllegalStateException("Connector endpoint no longer accepts channels");
            if (this.outboundResult != null) {
                this.outboundResult.failed(failure);
            }
            this.networkChannel.close();
            return;
        }
        this.lifecycle = LifecycleState.ACTIVE;

        String initial = this.initialMountId;
        if (initial != null) {
            this.getOrCreateChannel(initial);
        } else if (this.bindings.size() == 1) {
            this.getOrCreateChannel(this.bindings.keySet().iterator().next());
        }
    }

    private ConnectorChannel<M> getOrCreateChannel(String mountId) {
        ConnectorChannel<M> channel = this.protocolChannels.get(mountId);
        if (channel == null) {
            ProtocolMountBinding<M> binding = this.bindings.get(mountId);
            if (binding == null) {
                throw new IllegalArgumentException("Unknown protocol mount: " + mountId);
            }

            boolean isolatedClose = this.sharedRoutes && this.isInbound();
            String scheme = binding.config().scheme();
            ProtocolNetworkChannel<M> protocolChannel;
            protocolChannel = new ProtocolNetworkChannel<>(this.networkChannel, scheme, isolatedClose);
            long channelId = this.endpoint.manager().nextChannelId();
            Executor executor = protocolChannel::execute;
            ProtocolConfig config = binding.config();
            ProtocolFactory<M> factory = binding.factory();

            channel = new ConnectorChannel<>(this.endpoint, channelId, executor, protocolChannel, //
                    factory, this.outboundResult, config);
            protocolChannel.onClose(channel::closed);
            this.protocolChannels.put(mountId, channel);
            channel.connected();
        }
        return channel;
    }

    @Override
    public void receive(String mountId, M message) throws Exception {
        if (!this.isActive()) {
            return;
        }

        if (this.isInbound() && !this.endpoint.acceptingInbound(this.networkChannel)) {
            this.endpoint.closeInbound(this.networkChannel);
            return;
        }
        this.getOrCreateChannel(mountId).receive(message);
    }

    @Override
    public void receive(M message) {
        throw new IllegalStateException("Protocol route is missing");
    }

    @Override
    public void closed(Throwable failure) {
        if (this.lifecycle == LifecycleState.CLOSED) {
            return;
        }
        this.lifecycle = LifecycleState.CLOSED;

        Throwable cause = failure == null ? new IOException("Connection closed") : failure;
        for (ConnectorChannel<M> channel : this.protocolChannels.values()) {
            try {
                channel.networkClosed(cause);
            } catch (RuntimeException | Error cleanup) {
                logger.warn("Protocol session close failed", cleanup);
            }
        }

        if (this.outboundResult != null) {
            try {
                this.outboundResult.failed(cause);
            } catch (RuntimeException | Error cleanup) {
                logger.warn("Protocol connection failure notification failed", cleanup);
            }
        }

        this.terminationListener.run();
    }

    private boolean isInbound() {
        return this.outboundResult == null;
    }

    private boolean isActive() {
        return this.lifecycle == LifecycleState.ACTIVE;
    }
}
