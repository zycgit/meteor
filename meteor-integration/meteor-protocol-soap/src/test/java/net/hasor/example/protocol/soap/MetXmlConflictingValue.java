/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.annotation.MetXmlValue;

/** 非法映射示例：simpleContent 文本不能同时包含子元素。 */
public class MetXmlConflictingValue {
    @MetXmlValue
    public String text;
    @MetXmlElement
    public String child;
}
