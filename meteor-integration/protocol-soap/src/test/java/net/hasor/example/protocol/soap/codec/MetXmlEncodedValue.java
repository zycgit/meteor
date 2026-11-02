/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import java.util.List;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import net.hasor.example.protocol.soap.MetXmlState;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** SOAP Encoding 对象图中的 QName、属性和枚举仍与普通 XML 映射一致。 */
@MetType(name = "EncodedValue")
@JacksonXmlRootElement(namespace = "urn:meteor:xml:encoded")
public class MetXmlEncodedValue {
    @MetField(name = "identifier")
    @JacksonXmlProperty(isAttribute = true, namespace = "urn:meteor:xml:attributes")
    public String             id;
    @MetField(name = "value")
    @JacksonXmlProperty(namespace = "urn:meteor:xml:first")
    public String             first;
    @MetField(name = "value")
    @JacksonXmlProperty(namespace = "urn:meteor:xml:second")
    public String             second;
    public MetXmlState        state;
    public List<String>       entries;
    public MetXmlEncodedValue next;
}
