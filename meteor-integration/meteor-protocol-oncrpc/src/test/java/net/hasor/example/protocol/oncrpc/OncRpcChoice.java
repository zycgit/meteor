/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;

/** 与业务 IDL 对应的普通 Java 数据对象。 */
public class OncRpcChoice {
    private int    tag;
    private int    number;
    private String text;

    public int getTag() {
        return this.tag;
    }

    public void setTag(int tag) {
        this.tag = tag;
    }

    public int getNumber() {
        return this.number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
