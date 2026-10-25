/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 不可变 DTO 通过 Jackson 的构造器绑定读取。 */
public record MetXmlJacksonImmutableUser(String name, int age) {
    @JsonCreator
    public MetXmlJacksonImmutableUser(@JsonProperty("name") String name, @JsonProperty("age") int age) {
        this.name = name;
        this.age = age;
    }

    @Override
    @JsonProperty("name")
    public String name() {
        return this.name;
    }

    @Override
    @JsonProperty("age")
    public int age() {
        return this.age;
    }
}
