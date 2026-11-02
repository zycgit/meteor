/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
/** Dubbo 2.x/3.x 共用的二进制交换帧，正文所有权交给接收方。 */
public final class DubboFrame {
    private final byte   flags;
    private final byte   status;
    private final long   requestId;
    private final byte[] body;

    public DubboFrame(byte flags, byte status, long requestId, byte[] body) {
        this.flags = flags;
        this.status = status;
        this.requestId = requestId;
        this.body = body;
    }

    public byte flags() {
        return this.flags;
    }

    public boolean request() {
        return (this.flags & 0x80) != 0;
    }

    public boolean twoWay() {
        return (this.flags & 0x40) != 0;
    }

    public boolean event() {
        return (this.flags & 0x20) != 0;
    }

    public int serializationId() {
        return this.flags & 0x1f;
    }

    public byte status() {
        return this.status;
    }

    public long requestId() {
        return this.requestId;
    }

    public byte[] body() {
        return this.body;
    }
}
