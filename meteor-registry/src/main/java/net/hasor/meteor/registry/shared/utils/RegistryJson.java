/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.utils;
import java.io.IOException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** One JSON representation for all registry backends. */
public final class RegistryJson {
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = JsonMapper.builder() //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    private RegistryJson() {
    }

    /** Detached conversion between API, transport and stored JSON representations. */
    public static <T> T copy(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static String write(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String value, Class<T> type) throws IOException {
        return MAPPER.readValue(value, type);
    }

    /** 按正文值比较，避免对象身份或 Map 插入顺序导致重复写入。 */
    public static boolean sameData(Object left, Object right) {
        return MAPPER.valueToTree(left).equals(MAPPER.valueToTree(right));
    }
}
