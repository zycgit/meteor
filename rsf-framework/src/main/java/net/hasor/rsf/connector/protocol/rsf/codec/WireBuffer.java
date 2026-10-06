/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf.codec;
import java.util.Arrays;

/**
 * Bounded, heap-owned big-endian wire buffer. No pooled/network buffer escapes the transport.
 */
public final class WireBuffer {
    private static final int    LIMIT = 0xFFFFFF + 13;
    private              byte[] data;
    private              int    reader;
    private              int    writer;

    public WireBuffer() {
        this.data = new byte[128];
    }

    public WireBuffer(byte[] bytes) {
        this.data = bytes.clone();
        this.writer = bytes.length;
    }

    public int readableBytes() {
        return this.writer - this.reader;
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(this.data, this.reader, this.writer);
    }

    private void require(int count) {
        if (this.data == null || count < 0 || count > readableBytes()) {
            throw new IllegalArgumentException("Truncated RSF frame");
        }
    }

    private void reserve(int count) {
        if (this.data == null) {
            throw new IllegalStateException("Released buffer");
        }

        if (count < 0 || this.writer > LIMIT - count) {
            throw new IllegalArgumentException("RSF frame is too large");
        }

        if (this.writer + count > this.data.length) {
            this.data = Arrays.copyOf(this.data, Math.min(LIMIT, Math.max(this.writer + count, this.data.length * 2)));
        }
    }

    public WireBuffer writeByte(int value) {
        reserve(1);
        this.data[this.writer++] = (byte) value;
        return this;
    }

    public WireBuffer writeShort(int value) {
        return writeNumber(value, 2);
    }

    public WireBuffer writeMedium(int value) {
        return writeNumber(value, 3);
    }

    public WireBuffer writeInt(int value) {
        return writeNumber(value, 4);
    }

    public WireBuffer writeLong(long value) {
        return writeNumber(value, 8);
    }

    private WireBuffer writeNumber(long value, int size) {
        reserve(size);
        for (int i = size - 1; i >= 0; i--) {
            this.data[this.writer++] = (byte) (value >>> (i * 8));
        }
        return this;
    }

    public byte readByte() {
        require(1);
        return this.data[this.reader++];
    }

    public int readUnsignedByte() {
        return readByte() & 255;
    }

    public short readShort() {
        return (short) readNumber(2);
    }

    public int readInt() {
        return (int) readNumber(4);
    }

    public long readLong() {
        return readNumber(8);
    }

    private long readNumber(int size) {
        require(size);
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | (this.data[this.reader++] & 255);
        }
        return value;
    }

    public WireBuffer skipBytes(int size) {
        require(size);
        this.reader += size;
        return this;
    }

    public WireBuffer writeBytes(byte[] bytes) {
        reserve(bytes.length);
        System.arraycopy(bytes, 0, this.data, this.writer, bytes.length);
        this.writer += bytes.length;
        return this;
    }

    public WireBuffer writeBytes(WireBuffer source) {
        return writeBytes(source.toByteArray());
    }

    public void release() {
        this.data = null;
        this.reader = this.writer = 0;
    }
}
