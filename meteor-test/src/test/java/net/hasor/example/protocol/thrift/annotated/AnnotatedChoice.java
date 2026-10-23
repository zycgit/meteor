/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.protocol.thrift.annotation.ThriftUnion;

@ThriftUnion
public class AnnotatedChoice {
    @MetField(id = 3)
    private byte[] payload;
    @MetField(id = 1)
    private String text;
    @MetField(id = 2)
    private Long   count;

    public AnnotatedChoice() {
    }

    public byte[] getPayload() {
        return this.payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Long getCount() {
        return this.count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
