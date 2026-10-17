/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import java.util.List;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElementWrapper;

/** 显式区分元素可选、元素可空以及集合容器和成员的约束。 */
public class MetXmlNullableValue {
    @MetXmlElement(nillable = false)
    public String       optional;
    @MetXmlElement(required = true)
    public String       required = "present";
    @MetXmlElementWrapper(name = "entries", nillable = false)
    @MetXmlElement(name = "entry", required = true)
    public List<String> entries;
}
