/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.protocol.soap.annotation.MetXmlEnumValue;
import net.hasor.meteor.protocol.soap.annotation.MetXmlType;

/** Java 枚举值与 XML 契约值分别命名。 */
@MetXmlType(name = "OrderState", namespace = MetXmlOrder.TYPES)
public enum MetXmlState {
    @MetXmlEnumValue("ready-to-send") READY,
    @MetXmlEnumValue("already-sent") SENT
}
