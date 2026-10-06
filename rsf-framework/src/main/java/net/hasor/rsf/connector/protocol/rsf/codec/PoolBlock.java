/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf.codec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RSF/1 attribute pool. Each block owns heap data and needs no native-buffer finalizer.
 */
public class PoolBlock {
    public static final int          NULL_MARK   = -1;
    public static final int          DataMaxSize = 0xFFFFFF;
    public static final short        PoolMaxSize = 0x0FFF;
    private             List<byte[]> values      = new ArrayList<>();

    public void fillFrom(WireBuffer input) {
        int count = input.readShort() & 0xFFFF;
        if (count > PoolMaxSize) {
            throw new IllegalArgumentException("Invalid RSF pool size");
        }

        int[] lengths = new int[count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            int length = input.readInt();
            if (length < NULL_MARK || length > DataMaxSize) {
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
            decoded.add(length == NULL_MARK ? null : Arrays.copyOfRange(body, offset, offset + length));
            offset += Math.max(length, 0);
        }
        this.values = decoded;
    }

    public void fillTo(WireBuffer output) {
        requireOpen();
        output.writeShort(this.values.size());
        for (byte[] value : this.values) {
            output.writeInt(value == null ? NULL_MARK : value.length);
        }

        for (byte[] value : this.values) {
            if (value != null) {
                output.writeBytes(value);
            }
        }
    }

    public short pushData(byte[] data) {
        requireOpen();
        if (this.values.size() >= PoolMaxSize || (data != null && data.length > DataMaxSize)) {
            throw new IllegalArgumentException("RSF pool limit exceeded");
        }

        this.values.add(data == null ? null : data.clone());
        return (short) (this.values.size() - 1);
    }

    public byte[] readPool(short index) {
        requireOpen();
        byte[] value = this.values.get(index);
        return value == null ? null : value.clone();
    }

    public void release() {
        this.values = null;
    }

    private void requireOpen() {
        if (this.values == null) {
            throw new IllegalStateException("RSF pool has been released");
        }
    }
}
