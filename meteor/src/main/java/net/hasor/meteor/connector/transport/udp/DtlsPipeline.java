/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.meteor.connector.transport.SslPipeline;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplex;

/** Selects DTLS 1.2 for a secure UDP endpoint while reusing the endpoint's TLS certificate options. */
final class DtlsPipeline extends SslPipeline {
    private final int packetSize;

    DtlsPipeline(TransportConfig config, ClassLoader loader, int packetSize) {
        super(config, loader, "DTLS", "DTLSv1.2", "DTLS");
        this.packetSize = packetSize;
    }

    @Override
    protected SslDuplex createDuplex(SslConfig config) {
        return new DtlsDuplex(config, this.packetSize);
    }
}
