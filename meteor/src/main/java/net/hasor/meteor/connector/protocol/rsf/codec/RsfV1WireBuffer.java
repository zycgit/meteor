/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.util.Arrays;

/**
 * Bounded, heap-owned big-endian wire buffer. No pooled/network buffer escapes the transport.
 */
final class RsfV1WireBuffer {
    private static final int    LIMIT = 0xFFFFFF + 13;
    private              byte[] data;
    private              int    reader;
    private              int    writer;

    private RsfV1WireBuffer(byte[] data, int writer) {
        this.data = data;
        this.writer = writer;
    }

    static RsfV1WireBuffer output() {
        return new RsfV1WireBuffer(new byte[128], 0);
    }

    static RsfV1WireBuffer input(byte[] bytes) {
        return new RsfV1WireBuffer(bytes.clone(), bytes.length);
    }

    int readableBytes() {
        return this.writer - this.reader;
    }

    byte[] toByteArray() {
        return Arrays.copyOfRange(this.data, this.reader, this.writer);
    }

    private void require(int count) {
        if (this.data == null || count < 0 || count > this.readableBytes()) {
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
            int required = this.writer + count;
            int capacity = Math.min(LIMIT, Math.max(required, this.data.length * 2));
            this.data = Arrays.copyOf(this.data, capacity);
        }
    }

    RsfV1WireBuffer writeByte(int value) {
        this.reserve(1);
        this.data[this.writer++] = (byte) value;
        return this;
    }

    RsfV1WireBuffer writeShort(int value) {
        return this.writeNumber(value, 2);
    }

    RsfV1WireBuffer writeMedium(int value) {
        return this.writeNumber(value, 3);
    }

    RsfV1WireBuffer writeInt(int value) {
        return this.writeNumber(value, 4);
    }

    RsfV1WireBuffer writeLong(long value) {
        return this.writeNumber(value, 8);
    }

    private RsfV1WireBuffer writeNumber(long value, int size) {
        this.reserve(size);
        for (int i = size - 1; i >= 0; i--) {
            this.data[this.writer++] = (byte) (value >>> (i * 8));
        }
        return this;
    }

    byte readByte() {
        this.require(1);
        return this.data[this.reader++];
    }

    int readUnsignedByte() {
        return this.readByte() & 255;
    }

    short readShort() {
        return (short) this.readNumber(2);
    }

    int readInt() {
        return (int) this.readNumber(4);
    }

    long readLong() {
        return this.readNumber(8);
    }

    private long readNumber(int size) {
        this.require(size);
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | (this.data[this.reader++] & 255);
        }
        return value;
    }

    RsfV1WireBuffer skipBytes(int size) {
        this.require(size);
        this.reader += size;
        return this;
    }

    RsfV1WireBuffer writeBytes(byte[] bytes) {
        this.reserve(bytes.length);
        System.arraycopy(bytes, 0, this.data, this.writer, bytes.length);
        this.writer += bytes.length;
        return this;
    }

    RsfV1WireBuffer writeBytes(RsfV1WireBuffer source) {
        return this.writeBytes(source.toByteArray());
    }

    void release() {
        this.data = null;
        this.reader = this.writer = 0;
    }
}
