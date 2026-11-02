/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetField;

/** 单值容器由普通 DTO 层级表达，XML 结构与 Java 结构一致。 */
public class MetXmlWrappedUser {
    public Users users;

    public static class Users {
        @MetField(name = "user")
        public User value;
    }

    public static class User {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
    }
}
