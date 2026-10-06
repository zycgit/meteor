/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize.coder;
import java.io.IOException;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.hasor.meteor.serialize.SerializeCoder;

/** JSON serialization with UTF-8 on both sides of the wire. */
public class JsonSerializeCoder implements SerializeCoder {
    private final ObjectMapper mapper = new ObjectMapper()//
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)//
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)//
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)//
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String name() {
        return "Json";
    }

    @Override
    public byte[] encode(Object object) throws IOException {
        try {
            return this.mapper.writeValueAsBytes(object);
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
            return this.mapper.readValue(bytes, returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode JSON value", e);
        }
    }
}
