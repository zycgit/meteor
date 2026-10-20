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
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteReceiver;

/** Owns protocol sessions on one physical connection. Lifecycle callbacks follow channel order. */
final class RouteChannelDispatcher<M> implements RouteReceiver<M> {
    private static final Logger                           logger           = Logger.getLogger(RouteChannelDispatcher.class);
    private final        ConnectorEndpoint<M>             endpoint;
    private final        NetworkChannel<M>                networkChannel;
    private final        boolean                          outbound;
    private final        Map<String, ConnectorChannel<M>> protocolChannels = new LinkedHashMap<>();
    private              LifecycleState                   lifecycle        = LifecycleState.NEW;

    private enum LifecycleState {
        NEW,
        ACTIVE,
        CLOSED
    }

    RouteChannelDispatcher(ConnectorEndpoint<M> endpoint, NetworkChannel<M> channel, ConnectorChannel<M> initial) {
        this.endpoint = endpoint;
        this.networkChannel = channel;
        this.outbound = initial != null;
        if (initial != null) {
            this.protocolChannels.put(initial.getProtocol(), initial);
        }
    }

    @Override
    public void connected() {
        if (this.lifecycle != LifecycleState.NEW) {
            return;
        }

        boolean accepted = this.endpoint.registerConnection(this.networkChannel, !this.outbound);
        if (!accepted) {
            IllegalStateException failure = new IllegalStateException("Connector endpoint no longer accepts channels");
            this.closed(failure);
            this.networkChannel.close();
            return;
        }
        this.lifecycle = LifecycleState.ACTIVE;

        // 出站逻辑通道在发起连接前创建，物理连接激活后才启动会话。
        for (ConnectorChannel<M> channel : this.protocolChannels.values()) {
            channel.connected();
        }

        // 入站单协议端点立即启动握手，多协议端点等待报文选路。
        if (this.protocolChannels.isEmpty()) {
            String protocolName = this.endpoint.singleProtocol();
            if (protocolName != null) {
                this.getOrCreateChannel(protocolName);
            }
        }
    }

    private ConnectorChannel<M> getOrCreateChannel(String protocolName) {
        ConnectorChannel<M> channel = this.protocolChannels.get(protocolName);
        if (channel == null) {
            channel = this.endpoint.createChannel();
            this.protocolChannels.put(protocolName, channel);
            this.endpoint.attachChannel(channel, protocolName, this.networkChannel, this.outbound);
            if (this.isActive()) {
                channel.connected();
            }
        }
        return channel;
    }

    @Override
    public void receive(String protocolName, M message) throws Exception {
        if (!this.isActive()) {
            return;
        }

        if (!this.outbound && !this.endpoint.acceptingInbound(this.networkChannel)) {
            this.endpoint.closeInbound(this.networkChannel);
            return;
        }
        this.getOrCreateChannel(protocolName).receive(message);
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

        this.endpoint.unregisterConnection(this.networkChannel);
    }

    private boolean isActive() {
        return this.lifecycle == LifecycleState.ACTIVE;
    }
}