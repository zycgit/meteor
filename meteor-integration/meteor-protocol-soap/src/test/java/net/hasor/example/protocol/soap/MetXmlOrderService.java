/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;

/** 参数与结果元素命名由接口表达，操作名仍使用 Java 方法名。 */
public interface MetXmlOrderService {
    String NAMESPACE = "urn:hasor:meteor:soap:MetXmlOrderService";

    @MetReturn(name = "result")
    @JacksonXmlProperty(namespace = NAMESPACE)
    MetXmlOrder echo(@MetParam(name = "order") @JacksonXmlProperty(namespace = NAMESPACE) MetXmlOrder order);
}
