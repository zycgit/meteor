/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.meteor.serialize.SerializeCoder;

/** Hprose object serialization; type resolution follows the requested return type. */
public final class HproseSerializeCoder implements SerializeCoder {
    static final String SERIALIZE_TYPE = "Hprose";

    @Override
    public String name() {
        return SERIALIZE_TYPE;
    }

    @Override
    public byte[] encode(Object object) throws IOException {
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        try {
            new HproseWriter(binary).serialize(object);
            return binary.toByteArray();
        } catch (RuntimeException e) {
            throw new IOException("Cannot encode Hprose value", e);
        }
    }

    @Override
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException {
        Objects.requireNonNull(returnType, "returnType");
        if (bytes == null) {
            return null;
        }
        try {
            return new HproseReader(bytes).unserialize(returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode Hprose value", e);
        }
    }
}
