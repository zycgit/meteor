/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** 二进制属性与普通 base64 文本同时存在的值映射样本。 */
public class LiteralBinaryBean {
    private byte[]       data;
    private String       text;
    private List<byte[]> chunks;
    private int          dataReads;

    public byte[] getData() {
        this.dataReads++;
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<byte[]> getChunks() {
        return this.chunks;
    }

    public void setChunks(List<byte[]> chunks) {
        this.chunks = chunks;
    }

    @JsonIgnore
    public int getDataReads() {
        return this.dataReads;
    }
}
