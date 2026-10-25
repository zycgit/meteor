/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 二进制数据与外形类似 base64 的普通字符串必须保持各自的 Java 类型。 */
public class BinaryPayload {
    private byte[] data;
    private String encodedText;

    public byte[] getData() {
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getEncodedText() {
        return this.encodedText;
    }

    public void setEncodedText(String encodedText) {
        this.encodedText = encodedText;
    }
}
