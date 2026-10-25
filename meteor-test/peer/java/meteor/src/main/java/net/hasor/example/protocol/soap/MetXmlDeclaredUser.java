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
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** 泛型声明中可提前发现的用户类型，不依赖请求数据生成契约。 */
@MetType(name = "DeclaredUser")
@JacksonXmlRootElement(namespace = "urn:meteor:soap:declared-types")
public class MetXmlDeclaredUser {
    @MetField(name = "id")
    @JacksonXmlProperty(isAttribute = true)
    public String id;

    @MetField(name = "name")
    @JsonProperty(index = 1)
    public String name;

    @MetField(name = "age")
    @JsonProperty(index = 2)
    public int age;
}
