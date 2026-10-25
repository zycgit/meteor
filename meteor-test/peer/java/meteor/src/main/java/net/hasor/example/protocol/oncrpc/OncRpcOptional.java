/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;

/** 与业务 IDL 对应的普通 Java 数据对象。 */
public class OncRpcOptional {
    private int            value;
    private OncRpcOptional next;

    public int getValue() {
        return this.value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public OncRpcOptional getNext() {
        return this.next;
    }

    public void setNext(OncRpcOptional next) {
        this.next = next;
    }
}
