/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.udp;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.ChannelFactory;
import net.hasor.rsf.connector.transport.SocketConnector;

public final class UdpConnector extends SocketConnector<byte[]> {
    public UdpConnector(ConnectorConfig config, ClassLoader loader) {
        super(config, loader, socketConfig(config), true);
    }

    private static UdpSoConfig socketConfig(ConnectorConfig config) {
        UdpSoConfig socket = new UdpSoConfig();
        int limit = config.integer("maxDatagramSize", 65507);
        if (limit > 65507) {
            throw new IllegalArgumentException("maxDatagramSize must be between 1 and 65507");
        }

        socket.setRcvPacketSize(65536);
        return socket;
    }

    protected void initialize(ProtoBuildContext stack, InterAddress target, ChannelFactory<byte[]> factory, boolean accepted) throws Exception {
        UdpConnection.attach(stack, target, this.transport.nextExecutor(), this.config.integer("maxDatagramSize", 65507), factory);
    }
}
