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

/** 参数和返回值共享相同的单值包装映射。 */
public interface MetXmlWrappedUserService {
    String NAMESPACE = "urn:hasor:meteor:soap:MetXmlWrappedUserService";

    @MetReturn(name = "result")
    MetXmlWrappedUser echo(@MetParam(name = "request") MetXmlWrappedUser request);
}
