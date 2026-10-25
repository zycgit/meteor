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

/** 动态元素名和属性名通过普通服务 DTO 传输。 */
public interface MetXmlMapService {
    String NAMESPACE = "urn:hasor:meteor:soap:MetXmlMapService";

    @MetReturn(name = "result")
    MetXmlMapValue echo(@MetParam(name = "request") MetXmlMapValue request);
}
