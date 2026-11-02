/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;

/** simpleContent 的真实二进制文本可以作为附件，标量属性仍保持内联。 */
public class MetXmlBinaryText {
    @JacksonXmlText
    public byte[] value;
    @JacksonXmlProperty(isAttribute = true)
    public byte[] signature;
}
