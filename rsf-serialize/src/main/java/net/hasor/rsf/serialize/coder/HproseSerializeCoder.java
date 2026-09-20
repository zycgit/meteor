/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.serialize.coder;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.rsf.serialize.SerializeCoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Hprose object serialization; type resolution follows the requested return type. */
public class HproseSerializeCoder implements SerializeCoder {
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
