/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded, big-endian RFC 4506 output with canonical zero padding. */
public final class XdrOutput {
    private final ByteArrayOutputStream data;
    private final int                   maxBytes;
    private final int                   maxElements;
    private final int                   maxDepth;
    private       int                   elements;
    private       int                   depth;

    public XdrOutput(int maxBytes, int maxElements, int maxDepth) {
        if (maxBytes < 0 || maxElements < 0 || maxDepth < 1) {
            throw new IllegalArgumentException("Invalid XDR output limits");
        }

        this.maxBytes = maxBytes;
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
        this.data = new ByteArrayOutputStream(Math.min(maxBytes, 256));
    }

    public void writeInt(int value) {
        this.requireBytes(4);
        this.data.write(value >>> 24);
        this.data.write(value >>> 16);
        this.data.write(value >>> 8);
        this.data.write(value);
    }

    public void writeUnsignedInt(long value) {
        if (value < 0 || value > 0xffffffffL) {
            throw new IllegalArgumentException("XDR unsigned integer is outside its 32-bit range");
        }
        this.writeInt((int) value);
    }

    public void writeLong(long value) {
        this.requireBytes(8);
        this.writeInt((int) (value >>> 32));
        this.writeInt((int) value);
    }

    public void writeUnsignedLong(BigInteger value) {
        if (value == null || value.signum() < 0 || value.bitLength() > 64) {
            throw new IllegalArgumentException("XDR unsigned hyper integer is outside its 64-bit range");
        }
        this.writeLong(value.longValue());
    }

    public void writeBoolean(boolean value) {
        this.writeInt(value ? 1 : 0);
    }

    public void writeFloat(float value) {
        this.writeInt(Float.floatToRawIntBits(value));
    }

    public void writeDouble(double value) {
        this.writeLong(Double.doubleToRawLongBits(value));
    }

    public void writeFixedOpaque(byte[] value, int length) {
        if (value == null || length < 0 || value.length != length) {
            throw new IllegalArgumentException("XDR fixed opaque length does not match its declaration");
        }

        int padding = -length & 3;
        this.requireBytes((long) length + padding);
        this.data.write(value, 0, length);
        for (int index = 0; index < padding; index++) {
            this.data.write(0);
        }
    }

    public void writeOpaque(byte[] value, int maxLength) {
        if (value == null || maxLength < 0 || value.length > maxLength) {
            throw new IllegalArgumentException("XDR opaque length exceeds limit");
        }

        int padding = -value.length & 3;
        this.requireBytes(4L + value.length + padding);
        this.writeUnsignedInt(value.length);
        this.writeFixedOpaque(value, value.length);
    }

    /** Writes an RFC 4506 ASCII string; rejects characters that an ASCII encoder would replace. */
    public void writeString(String value, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException("XDR string cannot be null");
        }

        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 127) {
                throw new IllegalArgumentException("XDR string must contain ASCII characters");
            }
        }
        this.writeOpaque(value.getBytes(StandardCharsets.US_ASCII), maxLength);
    }

    void writeUtf8(String value, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException("XDR string cannot be null");
        }

        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
            encoder.onMalformedInput(CodingErrorAction.REPORT);
            encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            this.writeOpaque(bytes, maxLength);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 XDR string", e);
        }
    }

    /** Writes raw bytes without adding a length prefix or padding. */
    public void writeBytes(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("XDR bytes cannot be null");
        }

        this.requireBytes(value.length);
        this.data.write(value, 0, value.length);
    }

    public <T> void write(XdrCodec<T> codec, T value) {
        if (codec == null || this.depth >= this.maxDepth) {
            throw new IllegalArgumentException("Missing XDR codec or maximum depth exceeded");
        }

        this.depth++;
        try {
            codec.write(this, value);
        } finally {
            this.depth--;
        }
    }

    public byte[] toByteArray() {
        return this.data.toByteArray();
    }

    public int size() {
        return this.data.size();
    }

    void requireCount(int count) {
        if (count < 0 || count > this.maxElements - this.elements) {
            throw new IllegalArgumentException("XDR element count exceeds limit");
        }
        this.elements += count;
    }

    private void requireBytes(long length) {
        long resultingSize = this.data.size() + length;
        if (length < 0 || resultingSize > this.maxBytes) {
            throw new IllegalArgumentException("XDR message exceeds byte limit");
        }
    }
}