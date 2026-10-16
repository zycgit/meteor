/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.SocketConnector;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;

final class TcpConnector extends SocketConnector<byte[]> {
    private final int maxProbeSize;

    TcpConnector(TransportConfig config, TransportPipeline pipeline, ClassLoader loader) {
        super(config, pipeline, loader, socketConfig(config), false);
        this.maxProbeSize = config.integer("maxProbeSize", 256);
    }

    private static TcpSoConfig socketConfig(TransportConfig config) {
        TcpSoConfig socket = new TcpSoConfig();
        socket.setConnectTimeoutMs(config.connectTimeout());
        return socket;
    }

    protected void initialize(ProtoBuildContext stack, InterAddress target, //
            RouteTable<byte[]> routes, String initialMountId, ChannelListenerFactory<byte[]> factory) throws Exception {
        TcpChannel.attach(stack, target, this.transport.nextExecutor(), this.maxProbeSize, //
                routes, initialMountId, factory);
    }
}
