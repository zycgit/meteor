/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import java.util.List;
import net.hasor.example.protocol.soap.MetXmlState;
import net.hasor.meteor.protocol.soap.annotation.MetXmlAttribute;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.annotation.MetXmlType;

/** SOAP Encoding 对象图中的 QName、属性和枚举仍与普通 XML 映射一致。 */
@MetXmlType(name = "EncodedValue", namespace = "urn:meteor:xml:encoded")
public class MetXmlEncodedValue {
    @MetXmlAttribute(name = "identifier", namespace = "urn:meteor:xml:attributes")
    public String             id;
    @MetXmlElement(name = "value", namespace = "urn:meteor:xml:first")
    public String             first;
    @MetXmlElement(name = "value", namespace = "urn:meteor:xml:second")
    public String             second;
    public MetXmlState        state;
    public List<String>       entries;
    public MetXmlEncodedValue next;
}
