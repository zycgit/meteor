/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;

/** 普通 Java 接口通过 SOAP 传递 Jackson 映射的值。 */
public interface MetXmlJacksonPolymorphicService {
    @MetReturn(name = "result")
    MetXmlJacksonPolymorphicValue echo(@MetParam(name = "request") MetXmlJacksonPolymorphicValue request);
}
