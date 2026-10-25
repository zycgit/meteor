/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.hasor.meteor.annotation.MetField;

/** Object 的目标类型由原生 Jackson 注解指定。 */
public class MetXmlJacksonAsValue {
    @MetField(name = "payload")
    @JsonDeserialize(as = MetXmlJacksonUser.class)
    public Object value;
}
