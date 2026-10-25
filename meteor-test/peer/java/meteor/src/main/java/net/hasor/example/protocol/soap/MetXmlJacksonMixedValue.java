/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetField;

/** 普通对象层级、公共名称和 Jackson 自定义读写共同确定 XML。 */
public class MetXmlJacksonMixedValue {
    @JsonProperty(index = 0)
    public Settings settings = new Settings();

    @MetField(name = "name")
    @JacksonXmlProperty(namespace = "urn:meteor:jackson:left")
    @JsonProperty(index = 1)
    public String left;

    @MetField(name = "name")
    @JacksonXmlProperty(namespace = "urn:meteor:jackson:right")
    @JsonProperty(index = 2)
    public String right;

    @JacksonXmlProperty(namespace = "urn:meteor:jackson:payload")
    @JsonProperty(index = 3)
    public Details details = new Details();

    public static class Settings {
        @JacksonXmlProperty(isAttribute = true)
        public String version;
    }

    public static class Details {
        @MetField(name = "payload")
        @JacksonXmlProperty(namespace = "urn:meteor:jackson:payload")
        public MetXmlJacksonToken token;
    }
}
