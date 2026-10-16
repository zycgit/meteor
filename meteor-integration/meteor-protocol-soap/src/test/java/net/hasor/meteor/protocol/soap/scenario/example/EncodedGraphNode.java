/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

/** SOAP Encoding 的共享节点与循环引用示例。 */
public class EncodedGraphNode {
    private String           value;
    private EncodedGraphNode next;
    private EncodedGraphNode sibling;

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public EncodedGraphNode getNext() {
        return this.next;
    }

    public void setNext(EncodedGraphNode next) {
        this.next = next;
    }

    public EncodedGraphNode getSibling() {
        return this.sibling;
    }

    public void setSibling(EncodedGraphNode sibling) {
        this.sibling = sibling;
    }
}
