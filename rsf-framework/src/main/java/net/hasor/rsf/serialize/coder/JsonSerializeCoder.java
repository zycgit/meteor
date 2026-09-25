/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize.coder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import com.alibaba.fastjson.JSON;
import net.hasor.rsf.serialize.SerializeCoder;

/** JSON serialization with UTF-8 on both sides of the wire. */
public class JsonSerializeCoder implements SerializeCoder {
    @Override
    public byte[] encode(Object object) throws IOException {
        try {
            return JSON.toJSONString(object).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IOException("Cannot encode JSON value", e);
        }
    }

    @Override
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException {
        Objects.requireNonNull(returnType, "returnType");
        if (bytes == null) {
            return null;
        }
        try {
            return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode JSON value", e);
        }
    }
}
