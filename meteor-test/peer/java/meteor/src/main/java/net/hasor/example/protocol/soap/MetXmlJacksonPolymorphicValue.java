/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.hasor.meteor.annotation.MetField;

/** 类型判别使用 Jackson 的 kind 属性，不声明 Java 类名协议或 SOAP 类型注册。 */
public class MetXmlJacksonPolymorphicValue {
    @MetField(name = "payload")
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = MetXmlJacksonUser.class, name = "user"))
    public Object value;
}
