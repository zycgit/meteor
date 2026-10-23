/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.domain.ProtocolConfig;

/** TCP protocol SPI for selecting a protocol from the initial bytes of a connection. */
public interface TcpProtocolProvider extends ProtocolProvider<byte[]> {
    @Override
    default String transportType() {
        return "tcp";
    }

    @Override
    default Class<byte[]> messageType() {
        return byte[].class;
    }

    /** 根据连接的起始字节探测当前协议，不消费数据；前缀不足时返回 NEED_MORE。 */
    RouteProbeResult probeProtocol(ProtocolConfig config, byte[] prefix);
}