/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetField;

/** 业务属性和动态 Map 归属各自 DTO 元素，分组由普通 Java 对象表示。 */
public class MetXmlMapValue {
    @JacksonXmlProperty(isAttribute = true)
    @JsonProperty(index = 0)
    public String id;

    @JacksonXmlProperty(isAttribute = true)
    @JsonProperty(index = 1)
    public Map<String, Object> attributes;

    @JsonProperty(index = 5)
    public Settings settings = new Settings();

    @MetField(name = "elements")
    @JsonProperty(index = 10)
    public Map<String, Object> elements;

    public static class Settings {
        @JacksonXmlProperty(isAttribute = true)
        public String              version;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Object> values;
    }
}
