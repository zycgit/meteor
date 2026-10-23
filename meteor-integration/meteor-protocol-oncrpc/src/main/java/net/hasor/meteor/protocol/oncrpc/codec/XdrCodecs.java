/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** RFC 4506 scalar and composition codecs; application structs and discriminated unions implement XdrCodec directly. */
public final class XdrCodecs {
    public static final XdrCodec<Integer>    INT           = codec(XdrInput::readInt, XdrOutput::writeInt);
    public static final XdrCodec<Long>       UNSIGNED_INT  = codec(XdrInput::readUnsignedInt, XdrOutput::writeUnsignedInt);
    public static final XdrCodec<Long>       LONG          = codec(XdrInput::readLong, XdrOutput::writeLong);
    public static final XdrCodec<BigInteger> UNSIGNED_LONG = codec(XdrInput::readUnsignedLong, XdrOutput::writeUnsignedLong);
    public static final XdrCodec<Boolean>    BOOLEAN       = codec(XdrInput::readBoolean, XdrOutput::writeBoolean);
    public static final XdrCodec<Float>      FLOAT         = codec(XdrInput::readFloat, XdrOutput::writeFloat);
    public static final XdrCodec<Double>     DOUBLE        = codec(XdrInput::readDouble, XdrOutput::writeDouble);
    /** IEEE 754 binary128 is carried as its exact 16 bytes, without narrowing to Java double. */
    public static final XdrCodec<byte[]>     QUADRUPLE     = fixedOpaque(16);
    public static final XdrCodec<Void>       VOID          = codec(input -> null, (output, value) -> {
    });

    private XdrCodecs() {
    }

    public static XdrCodec<String> string(int maxLength) {
        requireMaximum(maxLength);
        return codec(input -> input.readString(maxLength), (output, value) -> output.writeString(value, maxLength));
    }

    /** Explicit UTF-8 string extension; its maximum counts encoded bytes rather than Java characters. */
    public static XdrCodec<String> utf8(int maxLength) {
        requireMaximum(maxLength);
        return codec(input -> input.readUtf8(maxLength), (output, value) -> output.writeUtf8(value, maxLength));
    }

    public static XdrCodec<byte[]> opaque(int maxLength) {
        requireMaximum(maxLength);
        return codec(input -> input.readOpaque(maxLength), (output, value) -> output.writeOpaque(value, maxLength));
    }

    public static XdrCodec<byte[]> fixedOpaque(int length) {
        requireMaximum(length);
        return codec(input -> input.readFixedOpaque(length), (output, value) -> output.writeFixedOpaque(value, length));
    }

    /** Variable-length XDR arrays use a List to retain their declaration order. */
    public static <T> XdrCodec<List<T>> list(XdrCodec<T> elementCodec, int maxLength) {
        requireElementCodec(elementCodec);
        requireMaximum(maxLength);
        return codec(input -> {
            int length = input.readCount(maxLength);
            List<T> values = new ArrayList<>(Math.min(length, 256));
            for (int index = 0; index < length; index++) {
                values.add(input.read(elementCodec));
            }
            return values;
        }, (output, values) -> {
            requireValues(values, maxLength);
            output.requireCount(values.size());
            output.writeUnsignedInt(values.size());
            for (T value : values) {
                output.write(elementCodec, value);
            }
        });
    }

    /** Fixed-length arrays omit the element-count prefix. */
    public static <T> XdrCodec<List<T>> fixedArray(XdrCodec<T> elementCodec, int length) {
        requireElementCodec(elementCodec);
        requireMaximum(length);
        return codec(input -> {
            input.requireCount(length);
            List<T> values = new ArrayList<>(Math.min(length, 256));
            for (int index = 0; index < length; index++) {
                values.add(input.read(elementCodec));
            }
            return values;
        }, (output, values) -> {
            if (values == null || values.size() != length) {
                throw new IllegalArgumentException("XDR fixed array length does not match its declaration");
            }
            output.requireCount(length);
            for (T value : values) {
                output.write(elementCodec, value);
            }
        });
    }

    /** Optional data is a boolean presence discriminant followed by one value when present. */
    public static <T> XdrCodec<T> optional(XdrCodec<T> valueCodec) {
        requireElementCodec(valueCodec);
        return codec(input -> {
            if (!input.readBoolean()) {
                return null;
            }
            return input.read(valueCodec);
        }, (output, value) -> {
            output.writeBoolean(value != null);
            if (value != null) {
                output.write(valueCodec, value);
            }
        });
    }

    /** Maps declared signed 32-bit enum values; unknown values must map to null or throw. */
    public static <T> XdrCodec<T> enumeration(IntFunction<T> reader, ToIntFunction<T> writer) {
        if (reader == null || writer == null) {
            throw new IllegalArgumentException("XDR enum mappings are required");
        }

        return codec(input -> {
            T value = reader.apply(input.readInt());
            if (value == null) {
                throw new IllegalArgumentException("Unknown XDR enum value");
            }
            return value;
        }, (output, value) -> {
            if (value == null) {
                throw new IllegalArgumentException("XDR enum cannot be null");
            }
            output.writeInt(writer.applyAsInt(value));
        });
    }

    private static <T> XdrCodec<T> codec(Function<XdrInput, T> reader, BiConsumer<XdrOutput, T> writer) {
        return new XdrCodec<>() {
            @Override
            public T read(XdrInput input) {
                return reader.apply(input);
            }

            @Override
            public void write(XdrOutput output, T value) {
                writer.accept(output, value);
            }
        };
    }

    private static void requireMaximum(int maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("XDR length limit must be non-negative");
        }
    }

    private static void requireElementCodec(XdrCodec<?> codec) {
        if (codec == null) {
            throw new IllegalArgumentException("XDR element codec is required");
        }
    }

    private static void requireValues(List<?> values, int maximum) {
        if (values == null || values.size() > maximum) {
            throw new IllegalArgumentException("XDR array length exceeds limit");
        }
    }
}