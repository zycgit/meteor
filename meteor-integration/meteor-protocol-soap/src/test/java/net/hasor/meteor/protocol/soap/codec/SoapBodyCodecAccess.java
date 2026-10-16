/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import net.hasor.meteor.protocol.soap.definition.*;
import org.w3c.dom.Element;

/** 为正文映射单元测试构造包内契约并转发调用，不包含断言。 */
public final class SoapBodyCodecAccess {
    private final SoapBodyCodec    codec;
    private final QName            wrapper;
    private final String           use;
    private final List<SoapPart>   parts   = new ArrayList<>();
    private final List<SoapChoice> choices = new ArrayList<>();

    private SoapBodyCodecAccess(SoapVersion version, QName wrapper, String use) {
        this.codec = new SoapBodyCodec(version, SoapMessageCodec.newXmlMapper());
        this.wrapper = wrapper;
        this.use = use;
    }

    public static SoapBodyCodecAccess literal(QName wrapper) {
        return new SoapBodyCodecAccess(SoapVersion.SOAP_11, wrapper, "literal");
    }

    public static SoapBodyCodecAccess encoded(QName wrapper, boolean soap12) {
        SoapVersion version = soap12 ? SoapVersion.SOAP_12 : SoapVersion.SOAP_11;
        return new SoapBodyCodecAccess(version, wrapper, "encoded");
    }

    public SoapBodyCodecAccess part(QName element, QName type, int minimum, int maximum) {
        this.parts.add(SoapDefinitionAccess.part(element.getLocalPart(), element, type, minimum, maximum));
        return this;
    }

    public SoapBodyCodecAccess choice(int minimum, int... indexes) {
        this.choices.add(SoapDefinitionAccess.choice(minimum, Arrays.stream(indexes).boxed().toList()));
        return this;
    }

    public Object[] read(Element body, Type... types) throws Exception {
        SoapBody binding = SoapDefinitionAccess.body(this.wrapper, this.parts, this.choices, this.use, null);
        return this.codec.read(body, binding, types);
    }

    public void write(Element body, Type[] types, Object[] values, Map<Element, byte[]> binaries) throws Exception {
        SoapBody binding = SoapDefinitionAccess.body(this.wrapper, this.parts, this.choices, this.use, null);
        this.codec.write(body, binding, types, values, binaries);
    }

    public static Object readLiteral(Element element, Type type) throws Exception {
        return new SoapLiteralValueCodec(SoapMessageCodec.newXmlMapper()).read(element, type);
    }

    public static void writeLiteral(Element element, Type type, Object value, //
            Map<Element, byte[]> binaries) throws Exception {
        new SoapLiteralValueCodec(SoapMessageCodec.newXmlMapper()).write(element, type, value, binaries);
    }
}
