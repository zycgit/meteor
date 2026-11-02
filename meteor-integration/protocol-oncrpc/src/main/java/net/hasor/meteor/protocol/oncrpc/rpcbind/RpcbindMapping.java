/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;

/** One RFC 1833 version 3/4 mapping; address is a transport's universal address. */
public final class RpcbindMapping {
    private final long   program;
    private final long   version;
    private final String netid;
    private final String address;
    private final String owner;

    public RpcbindMapping(long program, long version, String netid, String address, String owner) {
        this.program = program;
        this.version = version;
        this.netid = netid;
        this.address = address;
        this.owner = owner;
    }

    public long getProgram() {
        return this.program;
    }

    public long getVersion() {
        return this.version;
    }

    public String getNetid() {
        return this.netid;
    }

    public String getAddress() {
        return this.address;
    }

    public String getOwner() {
        return this.owner;
    }
}
