/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.domain.ProtocolConfig;

/** UDP protocol SPI for selecting a protocol from each complete datagram. */
public interface UdpProtocolProvider extends ProtocolProvider<byte[]> {
    @Override
    default String transportType() {
        return "udp";
    }

    @Override
    default Class<byte[]> messageType() {
        return byte[].class;
    }

    /** 根据完整数据报探测当前协议，不消费数据或等待后续数据报。 */
    RouteProbeResult probeProtocol(ProtocolConfig config, byte[] datagram);
}