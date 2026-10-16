/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;

final class UdpConnector extends SocketConnector<byte[]> {
    private static final int                      DTLS_OVERHEAD_RESERVE = 128;
    private final        int                      maxDatagramSize;
    private final        boolean                  dtlsEnabled;
    private final        TransportRuntimeServices runtimeServices;
    private final        int                      retransmitTimeout;
    private final        int                      handshakeTimeout;

    UdpConnector(TransportConfig config, TransportPipeline pipeline, TransportRuntimeServices runtimeServices) {
        super(config, pipeline, runtimeServices.classLoader(), socketConfig(), true);
        this.runtimeServices = runtimeServices;
        this.dtlsEnabled = Boolean.parseBoolean(config.option("tlsEnabled", "false"));
        int packetSize = this.dtlsEnabled ? dtlsPacketSize(config) : 0;
        this.maxDatagramSize = maxDatagramSize(config, this.dtlsEnabled, packetSize);
        this.retransmitTimeout = this.dtlsEnabled ? config.integer("dtlsRetransmitTimeout", 1000) : 0;
        this.handshakeTimeout = this.dtlsEnabled ? config.integer("dtlsHandshakeTimeout", config.connectTimeout()) : 0;
        if (this.dtlsEnabled) {
            this.installSecurity(new DtlsPipeline(config, runtimeServices.classLoader(), packetSize));
        }
    }

    @Override
    public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
            ChannelListenerFactory<byte[]> factory) throws Exception {
        if (address.getPort() == 0) {
            throw new IllegalArgumentException("UDP listen port must be between 1 and 65535");
        }
        return super.bind(address, routes, factory);
    }

    private static UdpSoConfig socketConfig() {
        UdpSoConfig socket = new UdpSoConfig();
        socket.setRcvPacketSize(65536);
        return socket;
    }

    private static int dtlsPacketSize(TransportConfig config) {
        int packetSize = config.integer("dtlsPacketSize", 1200);
        if (packetSize < 512 || packetSize > 65507) {
            throw new IllegalArgumentException("dtlsPacketSize must be between 512 and 65507");
        }
        return packetSize;
    }

    private static int maxDatagramSize(TransportConfig config, boolean dtlsEnabled, int packetSize) {
        // A secure application datagram must fit one DTLS record.
        // This preserves UDP message boundaries after decryption.
        int secureLimit = dtlsEnabled ? packetSize - DTLS_OVERHEAD_RESERVE : 65507;
        int maxDatagramSize = config.integer("maxDatagramSize", secureLimit);
        if (maxDatagramSize < 1 || maxDatagramSize > 65507) {
            throw new IllegalArgumentException("maxDatagramSize must be between 1 and 65507");
        }
        if (dtlsEnabled && maxDatagramSize > secureLimit) {
            String message = "DTLS maxDatagramSize must not exceed " + secureLimit;
            throw new IllegalArgumentException(message);
        }
        return maxDatagramSize;
    }

    protected void initialize(ProtoBuildContext stack, InterAddress target, //
            RouteTable<byte[]> routes, String initialMountId, ChannelListenerFactory<byte[]> factory) throws Exception {
        UdpChannel.attach(stack, target, this.transport.nextExecutor(), this.maxDatagramSize, this.dtlsEnabled, //
                this.runtimeServices, this.retransmitTimeout, this.handshakeTimeout, routes, initialMountId, factory);
    }
}
