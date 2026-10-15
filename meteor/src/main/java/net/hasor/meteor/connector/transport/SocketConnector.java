/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.SoConfig;

/**
 * Owns network resources only. The receiver is supplied by the layer assembling the channel.
 */
public abstract class SocketConnector<M> implements NetworkConnector<M> {
    protected final TransportConfig             config;
    protected final SocketRuntime               transport;
    private final   boolean                     sharedListenSocket;
    private final   Set<SocketAdmissionTracker> admissionTrackers = ConcurrentHashMap.newKeySet();

    protected SocketConnector(TransportConfig config, TransportPipeline pipeline, ClassLoader loader, //
            SoConfig socket, boolean sharedListenSocket) {
        this.config = config;
        this.transport = new SocketRuntime(config, pipeline, loader, socket);
        this.sharedListenSocket = sharedListenSocket;
        if (!sharedListenSocket && Boolean.parseBoolean(config.option("tlsEnabled", "false"))) {
            this.transport.installSecurity(SslPipeline.newTls(config, loader));
        }
    }

    protected final void installSecurity(SslPipeline security) {
        this.transport.installSecurity(security);
    }

    public NetworkListen bind(InterAddress address, RouteTable<M> routes, //
            ChannelListenerFactory<M> factory) throws Exception {
        SocketAdmissionTracker admission = new SocketAdmissionTracker();
        this.admissionTrackers.add(admission);
        try {
            SocketListen listen = new SocketListen(address, this.transport.bind(address, stack -> {
                NetChannel socket = (NetChannel) stack.getChannel();
                if (!admission.register(socket)) {
                    return;
                }

                InetSocketAddress remote = (InetSocketAddress) stack.getChannel().getRemoteAddr();
                String host = remote.getAddress().getHostAddress();
                InterAddress target = new InterAddress(address.getSchema(), host, remote.getPort(), "unknown");
                ChannelListenerFactory<M> tracked = channel -> {
                    try {
                        ChannelListener<M> receiver = factory.createListener(channel);
                        return admission.transferOnConnected(socket, receiver);
                    } catch (Exception | Error failure) {
                        admission.reject(socket);
                        throw failure;
                    }
                };

                this.attach(stack, target, routes, null, tracked);
            }), this.sharedListenSocket, admission);

            listen.onClose(() -> this.admissionTrackers.remove(admission));
            return listen;
        } catch (Exception | Error failure) {
            this.admissionTrackers.remove(admission);
            admission.suspend();
            throw failure;
        }
    }

    public Future<Void> connect(InterAddress target, RouteTable<M> routes, String initialMountId, //
            ChannelListenerFactory<M> factory) {
        BasicFuture<Void> result = new BasicFuture<>();
        Future<NetChannel> connecting = this.transport.connect(target, stack -> {
            this.attach(stack, target, routes, initialMountId, factory);
        });

        result.onCancel(done -> {
            connecting.cancel();
            if (connecting.getResult() != null) {
                connecting.getResult().closeNow();
            }
        });

        connecting.onFinal(done -> {
            if (done.isCancelled()) {
                result.cancel();
            } else if (done.getCause() != null) {
                result.failed(done.getCause());
            } else if (!result.completed(null) && result.isCancelled()) {
                done.getResult().closeNow();
            }
        });
        return result;
    }

    private void attach(ProtoBuildContext stack, InterAddress target, RouteTable<M> routes, String initialMountId, //
            ChannelListenerFactory<M> factory) {
        try {
            this.initialize(stack, target, routes, initialMountId, factory);
        } catch (Exception error) {
            throw ExceptionUtils.toRuntime(error);
        }
    }

    protected abstract void initialize(ProtoBuildContext stack, InterAddress target, RouteTable<M> routes, //
            String initialMountId, ChannelListenerFactory<M> factory) throws Exception;

    public void close() {
        for (SocketAdmissionTracker admission : this.admissionTrackers) {
            admission.suspend();
        }
        this.admissionTrackers.clear();
        this.transport.close();
    }
}
