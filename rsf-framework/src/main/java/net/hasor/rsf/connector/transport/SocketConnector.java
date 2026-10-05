/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import java.net.InetSocketAddress;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.SoConfig;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;

/**
 * Owns network resources only. The receiver is supplied by the layer assembling the channel.
 */
public abstract class SocketConnector<M> implements NetworkConnector<M> {
    protected final ConnectorConfig config;
    protected final SocketTransport transport;
    private final   boolean         datagram;

    protected SocketConnector(ConnectorConfig config, ClassLoader loader, SoConfig socket, boolean datagram) {
        this.config = config;
        this.transport = new SocketTransport(config, loader, socket);
        this.datagram = datagram;
    }

    public NetworkListen bind(InterAddress address, ChannelFactory<M> factory) throws Exception {
        return new SocketListen(address, this.transport.bind(address, stack -> {
            InetSocketAddress remote = (InetSocketAddress) stack.getChannel().getRemoteAddr();
            InterAddress target = new InterAddress(address.getSchema(), remote.getAddress().getHostAddress(), remote.getPort(), "unknown");
            this.attach(stack, target, factory, true);
        }), this.datagram);
    }

    public Future<Void> connect(InterAddress target, ChannelFactory<M> factory) {
        BasicFuture<Void> result = new BasicFuture<>();
        Future<NetChannel> connecting = this.transport.connect(target, stack -> this.attach(stack, target, factory, false));

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

    private void attach(ProtoBuildContext stack, InterAddress target, ChannelFactory<M> factory, boolean accepted) {
        try {
            this.initialize(stack, target, factory, accepted);
        } catch (Exception error) {
            throw ExceptionUtils.toRuntime(error);
        }
    }

    protected abstract void initialize(ProtoBuildContext stack, InterAddress target, ChannelFactory<M> factory, boolean accepted) throws Exception;

    public void close() {
        this.transport.close();
    }
}
