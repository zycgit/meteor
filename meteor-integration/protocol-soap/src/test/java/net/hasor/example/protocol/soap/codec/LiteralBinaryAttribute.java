/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** 属性中的二进制保留内联文本，不作为 MTOM 元素。 */
public class LiteralBinaryAttribute {
    private byte[] data;

    @JacksonXmlProperty(isAttribute = true)
    public byte[] getData() {
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
