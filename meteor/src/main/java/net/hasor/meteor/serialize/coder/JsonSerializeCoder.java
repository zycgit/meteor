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
import com.fasterxml.jackson.databind.json.JsonMapper;
import net.hasor.meteor.serialize.SerializeCoder;

/** JSON serialization with UTF-8 on both sides of the wire. */
public class JsonSerializeCoder implements SerializeCoder {
    private static final ObjectMapper          JSON = JsonMapper.builder() //
            .serializationInclusion(JsonInclude.Include.NON_NULL)   //
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)      //
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private volatile     JsonSerializeDelegate delegate;

    /** 配置此编码器独享的用户委托，不影响框架内部的 JSON 处理。 */
    public JsonSerializeCoder setDelegate(JsonSerializeDelegate delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        return this;
    }

    @Override
    public String name() {
        return "Json";
    }

    @Override
    public byte[] encode(Object object) throws IOException {
        try {
            JsonSerializeDelegate delegate = this.delegate;
            return delegate == null ? JSON.writeValueAsBytes(object) : delegate.encode(object);
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
            JsonSerializeDelegate delegate = this.delegate;
            return delegate == null ? JSON.readValue(bytes, returnType) : delegate.decode(bytes, returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode JSON value", e);
        }
    }
}
