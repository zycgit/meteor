/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.tcp;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.ChannelFactory;
import net.hasor.rsf.connector.transport.SocketConnector;

public class TcpConnector extends SocketConnector<byte[]> {
    public TcpConnector(ConnectorConfig config, ClassLoader loader) {
        super(config, loader, socketConfig(config), false);
    }

    private static TcpSoConfig socketConfig(ConnectorConfig config) {
        TcpSoConfig socket = new TcpSoConfig();
        socket.setConnectTimeoutMs(config.connectTimeout());
        return socket;
    }

    protected void initialize(ProtoBuildContext stack, InterAddress target, ChannelFactory<byte[]> factory, boolean accepted) throws Exception {
        TcpConnection.attach(stack, target, this.transport.nextExecutor(), this.config.integer("maxProbeSize", 256), factory);
    }
}
