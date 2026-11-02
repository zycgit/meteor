/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayOutputStream;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** 在缓冲区扩容之前检查消息大小，完整写入或直接失败。 */
final class TripleBoundedOutputStream extends ByteArrayOutputStream {
    private final int maximum;

    TripleBoundedOutputStream(int maximum) {
        super(Math.min(256, maximum));
        this.maximum = maximum;
    }

    @Override
    public void write(int value) {
        this.check(1);
        super.write(value);
    }

    @Override
    public void write(byte[] value, int offset, int length) {
        this.check(length);
        super.write(value, offset, length);
    }

    private void check(int additional) {
        if ((long) this.count + additional > this.maximum) {
            throw new GrpcStatusException(8, "Triple message exceeds configured maxMessageSize", null, null);
        }
    }
}
