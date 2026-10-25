/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetField;

/** 非法映射示例：simpleContent 文本不能同时包含子元素。 */
public class MetXmlConflictingValue {
    @JacksonXmlText
    public String text;
    @MetField
    public String child;
}
