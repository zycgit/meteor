/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.util.List;
import java.util.Map;

/** 一次 Jackson 绑定产生的逻辑值，保留二进制、DOM 和序列化器提供的运行时上下文。 */
final class SoapJacksonValue {
    private final Object value;
    private final Object source;

    SoapJacksonValue(Object value, Object source) {
        this.value = value;
        this.source = source;
    }

    Object value() {
        return this.value;
    }

    Object source() {
        return this.source;
    }

    boolean object() {
        return this.value instanceof Map<?, ?>;
    }

    boolean array() {
        return this.value instanceof List<?>;
    }

    @SuppressWarnings("unchecked")
    Map<String, SoapJacksonValue> fields() {
        return (Map<String, SoapJacksonValue>) this.value;
    }

    @SuppressWarnings("unchecked")
    List<SoapJacksonValue> items() {
        return (List<SoapJacksonValue>) this.value;
    }
}
