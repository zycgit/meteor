/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;

/** 父接口中的类型参数由具体服务接口决定。 */
public interface MetXmlGenericService<T> {
    @MetReturn(name = "result")
    Map<String, List<T>> echo(@MetParam(name = "request") Map<String, List<T>> request);
}
