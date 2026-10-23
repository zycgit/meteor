/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 对外 XML 由用户的 Jackson Module 决定，内部字段名不是 XML 契约。 */
public record MetXmlJacksonToken(String value) {
}
