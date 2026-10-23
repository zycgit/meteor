/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetType;

/** Java 枚举值与 XML 契约值分别命名。 */
@MetType(name = "OrderState")
@JacksonXmlRootElement(namespace = MetXmlOrder.TYPES)
public enum MetXmlState {
    @MetEnumValue(name = "ready-to-send") READY,
    @MetEnumValue(name = "already-sent") SENT
}