/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import javax.xml.namespace.QName;

/** 一个 Java 参数或结果在 SOAP 消息中的元素及 XML 类型。 */
public final class SoapPart {
    private final String name;
    private final QName  element;
    private final QName  type;
    private final int    minOccurs;
    private final int    maxOccurs;

    SoapPart(String name, QName element, QName type, int minOccurs, int maxOccurs) {
        this.name = name;
        this.element = element;
        this.type = type;
        this.minOccurs = minOccurs;
        this.maxOccurs = maxOccurs;
    }

    public String name() {
        return this.name;
    }

    public QName element() {
        return this.element;
    }

    public QName type() {
        return this.type;
    }

    public int minOccurs() {
        return this.minOccurs;
    }

    public int maxOccurs() {
        return this.maxOccurs;
    }
}
