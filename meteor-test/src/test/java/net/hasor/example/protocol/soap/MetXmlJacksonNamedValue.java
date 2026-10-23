/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 原生 Jackson 名称与空值包含规则可以直接用于普通 DTO。 */
public class MetXmlJacksonNamedValue {
    @JsonProperty("display_name")
    public String name;

    @JsonProperty("memo")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String memo;

    @JsonProperty("retained")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String retained;
}
