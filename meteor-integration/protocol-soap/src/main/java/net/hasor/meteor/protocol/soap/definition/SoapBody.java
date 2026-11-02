/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.List;
import javax.xml.namespace.QName;
import net.hasor.cobble.StringUtils;

/** 一个方向的 SOAP Body；无 wrapper 时各 part 直接位于 Body 下。 */
public final class SoapBody {
    private final QName            wrapper;
    private final List<SoapPart>   parts;
    private final List<SoapChoice> choices;
    private final String           use;
    private final String           encodingStyle;

    SoapBody(QName wrapper, List<SoapPart> parts, List<SoapChoice> choices, String use, String encodingStyle) {
        this.wrapper = wrapper;
        this.parts = List.copyOf(parts);
        this.choices = List.copyOf(choices);
        this.use = use;
        this.encodingStyle = encodingStyle;
    }

    public QName wrapper() {
        return this.wrapper;
    }

    public List<SoapPart> parts() {
        return this.parts;
    }

    public List<SoapChoice> choices() {
        return this.choices;
    }

    String use() {
        return this.use;
    }

    String encodingStyle() {
        return this.encodingStyle;
    }

    public boolean encoded() {
        return StringUtils.equals("encoded", this.use);
    }

    List<QName> elements() {
        return this.parts.stream().map(SoapPart::element).toList();
    }

    boolean matches(List<QName> elements) {
        if (this.wrapper != null) {
            return !elements.isEmpty() && this.wrapper.equals(elements.get(0)) //
                    && (this.encoded() || elements.size() == 1);
        }
        if (this.encoded()) {
            // 引用定义可以与 bare accessors 并列，实际引用属性由值解码器校验。
            List<QName> declared = this.elements();
            elements = elements.stream().filter(declared::contains).toList();
        }
        int offset = 0;
        for (SoapPart part : this.parts) {
            int count = 0;
            while (offset < elements.size() && part.element().equals(elements.get(offset))) {
                if (part.maxOccurs() >= 0 && count == part.maxOccurs()) {
                    break;
                }
                offset++;
                count++;
            }
            if (count < part.minOccurs()) {
                return false;
            }
        }
        return offset == elements.size();
    }
}
