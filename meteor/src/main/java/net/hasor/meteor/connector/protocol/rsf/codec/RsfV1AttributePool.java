/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RSF/1 attribute pool. Each pool owns heap data and needs no native-buffer finalizer. */
class RsfV1AttributePool {
    static final int          NULL_MARKER     = -1;
    static final int          MAX_VALUE_SIZE  = 0xFFFFFF;
    static final short        MAX_VALUE_COUNT = 0x0FFF;
    private      List<byte[]> values          = new ArrayList<>();

    void readFrom(RsfV1WireBuffer input) {
        int count = input.readShort() & 0xFFFF;
        if (count > MAX_VALUE_COUNT) {
            throw new IllegalArgumentException("Invalid RSF pool size");
        }

        int[] lengths = new int[count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            int length = input.readInt();
            if (length < NULL_MARKER || length > MAX_VALUE_SIZE) {
                throw new IllegalArgumentException("Invalid RSF pool item length");
            }
            lengths[i] = length;
            total += Math.max(length, 0);
        }

        if (total != input.readableBytes()) {
            throw new IllegalArgumentException("RSF pool data length mismatch");
        }

        byte[] body = input.toByteArray();
        List<byte[]> decoded = new ArrayList<>(count);
        int offset = 0;
        for (int length : lengths) {
            decoded.add(length == NULL_MARKER ? null : Arrays.copyOfRange(body, offset, offset + length));
            offset += Math.max(length, 0);
        }
        this.values = decoded;
    }

    void writeTo(RsfV1WireBuffer output) {
        this.requireOpen();
        output.writeShort(this.values.size());
        for (byte[] value : this.values) {
            output.writeInt(value == null ? NULL_MARKER : value.length);
        }

        for (byte[] value : this.values) {
            if (value != null) {
                output.writeBytes(value);
            }
        }
    }

    short addValue(byte[] data) {
        this.requireOpen();
        if (this.values.size() >= MAX_VALUE_COUNT || (data != null && data.length > MAX_VALUE_SIZE)) {
            throw new IllegalArgumentException("RSF pool limit exceeded");
        }

        this.values.add(data == null ? null : data.clone());
        return (short) (this.values.size() - 1);
    }

    byte[] getValue(short index) {
        this.requireOpen();
        byte[] value = this.values.get(index);
        return value == null ? null : value.clone();
    }

    void release() {
        this.values = null;
    }

    private void requireOpen() {
        if (this.values == null) {
            throw new IllegalStateException("RSF pool has been released");
        }
    }
}
