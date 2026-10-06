/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.*;

/**
 * Bridges an application protocol to the framework, without depending on the network engine.
 */
public final class EndpointConnector<M> extends AbstractConnector {
    private       NetworkConnector<M>             network;
    private final Set<NetworkChannel<M>>          connections = ConcurrentHashMap.newKeySet();
    private final NetworkConnectorFactory<M>      transport;
    private final Map<String, ProtocolFactory<M>> protocols;
    private final Map<String, NetworkRoute<M>>    routes;

    public EndpointConnector(ConnectorConfig config, ConnectorManager manager, NetworkConnectorFactory<M> transport, Map<String, ProtocolFactory<M>> protocols, Map<String, NetworkRoute<M>> routes) {
        super(config, manager);
        this.transport = transport;
        this.protocols = protocols;
        this.routes = routes;
    }

    protected void initialize() {
        this.network = this.transport.create(this.config, this.manager.context().getClassLoader());
    }

    protected Future<MetListen> listen(InterAddress address, ReceivedListener receiver) throws Exception {
        NetworkListen networkListen = this.network.bind(address, channel -> {
            return this.attach(channel, receiver, null);
        });

        MetListen listen = new ProtocolListen(this.config.listenType(), networkListen, receiver);
        networkListen.onClose(() -> this.onListenClosed(listen));
        if (!this.onListen(listen)) {
            listen.close();
            throw new IllegalStateException("Connector no longer accepts listeners");
        } else {
            return new BasicFuture<>(listen);
        }
    }

    protected Future<MetChannel> openSession(InterAddress address, ReceivedListener receiver) {
        BasicFuture<MetChannel> result = new BasicFuture<>();
        Future<Void> connecting = this.network.connect(address, channel -> {
            result.onCancel(done -> {
                channel.close();
            });
            return this.attach(channel, receiver, result);
        });

        result.onCancel(done -> {
            connecting.cancel();
        });

        connecting.onFailed(done -> {
            result.failed(done.getCause());
        }).onCancel(done -> result.cancel());
        return result;
    }

    private ChannelListener<M> attach(NetworkChannel<M> channel, ReceivedListener receiver, BasicFuture<MetChannel> connecting) {
        this.connections.add(channel);
        try {
            return new ProtocolRouter<>(this, channel, receiver, this.protocols, this.routes, connecting, this.transport.sharedRoutes(), () -> this.connections.remove(channel));
        } catch (RuntimeException | Error failure) {
            this.connections.remove(channel);
            channel.close();
            throw failure;
        }
    }

    protected void doClose() {
        List<Future<Void>> draining = new ArrayList<>();
        try {
            for (NetworkChannel<M> channel : this.connections) {
                draining.add(channel.drainAndClose());
            }

            for (Future<Void> future : draining) {
                try {
                    future.get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    // The transport close below reclaims failed connections too.
                }
            }
        } finally {
            if (this.network != null) {
                this.network.close();
            }
            this.connections.clear();
        }
    }

    protected ConnectorManager manager() {
        return this.manager;
    }

    protected boolean writable() {
        return this.acceptsWrites();
    }

    protected void connected(MetChannel channel) {
        if (this.acceptsWrites()) {
            this.fireChannelConnected(channel);
        } else {
            channel.close();
        }
    }

    protected void disconnected(MetChannel channel) {
        this.fireChannelClosed(channel);
    }
}
