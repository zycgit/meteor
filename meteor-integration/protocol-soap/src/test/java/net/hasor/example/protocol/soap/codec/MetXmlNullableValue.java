/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import net.hasor.meteor.annotation.MetField;

/** 显式区分元素可选、元素可空，以及集合容器和成员共享的约束。 */
public class MetXmlNullableValue {
    @MetField
    @JsonSetter(nulls = Nulls.FAIL)
    public String       optional;
    @MetField
    @JsonProperty(required = true)
    public String       required = "present";
    @MetField(name = "entry")
    @JacksonXmlElementWrapper(localName = "entries")
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    public List<String> entries  = List.of("present");
}
