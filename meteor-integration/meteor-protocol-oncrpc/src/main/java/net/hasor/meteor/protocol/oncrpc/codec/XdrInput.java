/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, big-endian RFC 4506 input. The supplied bytes are borrowed and must remain unchanged while decoding. */
public final class XdrInput {
    private final byte[] data;
    private final int    maxElements;
    private final int    maxDepth;
    private       int    elements;
    private       int    position;
    private       int    depth;

    public XdrInput(byte[] data, int maxBytes, int maxElements, int maxDepth) {
        if (data == null || maxBytes < 0 || data.length > maxBytes || maxElements < 0 || maxDepth < 1) {
            throw new IllegalArgumentException("Invalid XDR input or limits");
        }

        this.data = data;
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
    }

    public int readInt() {
        this.requireBytes(4);
        int offset = this.position;
        this.position += 4;
        int value = (this.data[offset] & 255) << 24;
        value |= (this.data[offset + 1] & 255) << 16;
        value |= (this.data[offset + 2] & 255) << 8;
        return value | this.data[offset + 3] & 255;
    }

    public long readUnsignedInt() {
        return Integer.toUnsignedLong(this.readInt());
    }

    public long readLong() {
        long high = this.readUnsignedInt();
        long low = this.readUnsignedInt();
        return high << 32 | low;
    }

    public BigInteger readUnsignedLong() {
        return new BigInteger(1, this.readBytes(8));
    }

    public boolean readBoolean() {
        int value = this.readInt();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("XDR boolean must be zero or one");
        }

        return value == 1;
    }

    public float readFloat() {
        return Float.intBitsToFloat(this.readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(this.readLong());
    }

    /** Reads exactly length opaque bytes and consumes their canonical zero padding. */
    public byte[] readFixedOpaque(int length) {
        this.requireBytes(length);
        int padding = this.padding(length);
        long total = (long) length + padding;
        if (total > this.remaining()) {
            throw new IllegalArgumentException("Truncated XDR opaque padding");
        }

        int end = this.position + length;
        for (int index = 0; index < padding; index++) {
            if (this.data[end + index] != 0) {
                throw new IllegalArgumentException("XDR opaque padding must be zero");
            }
        }

        byte[] value = Arrays.copyOfRange(this.data, this.position, end);
        this.position = end + padding;
        return value;
    }

    public byte[] readOpaque(int maxLength) {
        int length = this.readLength(maxLength);
        return this.readFixedOpaque(length);
    }

    /** RFC 4506 strings contain ASCII bytes; UTF-8 is an explicit XdrCodecs extension. */
    public String readString(int maxLength) {
        byte[] bytes = this.readOpaque(maxLength);
        for (byte value : bytes) {
            if (value < 0) {
                throw new IllegalArgumentException("XDR string must contain ASCII characters");
            }
        }

        return new String(bytes, StandardCharsets.US_ASCII);
    }

    String readUtf8(int maxLength) {
        byte[] bytes = this.readOpaque(maxLength);
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 XDR string", e);
        }
    }

    /** Reads raw bytes without a length prefix or padding, for surrounding RPC envelopes. */
    public byte[] readBytes(int length) {
        this.requireBytes(length);
        int end = this.position + length;
        byte[] value = Arrays.copyOfRange(this.data, this.position, end);
        this.position = end;
        return value;
    }

    public byte[] readRemaining() {
        return this.readBytes(this.remaining());
    }

    public int remaining() {
        return this.data.length - this.position;
    }

    public int position() {
        return this.position;
    }

    public void requireEnd() {
        if (this.remaining() != 0) {
            throw new IllegalArgumentException("Trailing XDR data");
        }
    }

    public <T> T read(XdrCodec<T> codec) {
        if (codec == null || this.depth >= this.maxDepth) {
            throw new IllegalArgumentException("Missing XDR codec or maximum depth exceeded");
        }

        this.depth++;
        try {
            return codec.read(this);
        } finally {
            this.depth--;
        }
    }

    int readCount(int maximum) {
        int effectiveMaximum = Math.min(maximum, this.maxElements);
        int count = this.readLength(effectiveMaximum);
        this.requireCount(count);
        return count;
    }

    void requireCount(int count) {
        if (count < 0 || count > this.maxElements - this.elements) {
            throw new IllegalArgumentException("XDR element count exceeds limit");
        }
        this.elements += count;
    }

    private int readLength(int maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("XDR length limit must be non-negative");
        }

        long length = this.readUnsignedInt();
        if (length > maximum) {
            throw new IllegalArgumentException("XDR length exceeds limit");
        }
        return (int) length;
    }

    private void requireBytes(int length) {
        if (length < 0 || length > this.remaining()) {
            throw new IllegalArgumentException("Truncated XDR value");
        }
    }

    private int padding(int length) {
        return -length & 3;
    }
}