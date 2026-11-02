/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;

/** One RFC 1833 version 2 port mapping; protocol is IPPROTO_TCP (6) or IPPROTO_UDP (17). */
public final class PortMapperMapping {
    private final long program;
    private final long version;
    private final long protocol;
    private final long port;

    public PortMapperMapping(long program, long version, long protocol, long port) {
        this.program = program;
        this.version = version;
        this.protocol = protocol;
        this.port = port;
    }

    public long getProgram() {
        return this.program;
    }

    public long getVersion() {
        return this.version;
    }

    public long getProtocol() {
        return this.protocol;
    }

    public long getPort() {
        return this.port;
    }
}
