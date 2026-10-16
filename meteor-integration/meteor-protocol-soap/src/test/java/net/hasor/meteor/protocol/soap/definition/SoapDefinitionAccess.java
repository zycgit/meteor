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

/** 为运行时单元测试构造包内契约对象，不包含断言。 */
public final class SoapDefinitionAccess {
    private SoapDefinitionAccess() {
    }

    public static SoapBody body(QName wrapper, List<SoapPart> parts, List<SoapChoice> choices, //
            String use, String encodingStyle) {
        return new SoapBody(wrapper, parts, choices, use, encodingStyle);
    }

    public static SoapPart part(String name, QName element, QName type, int minimum, int maximum) {
        return new SoapPart(name, element, type, minimum, maximum);
    }

    public static SoapChoice choice(int minimum, List<Integer> indexes) {
        return new SoapChoice(minimum, indexes);
    }
}
