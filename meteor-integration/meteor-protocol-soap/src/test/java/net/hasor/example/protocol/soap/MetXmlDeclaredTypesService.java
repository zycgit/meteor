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
import java.util.Set;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;

/** 容器使用声明泛型，Object 明确表示开放 XML 内容。 */
public interface MetXmlDeclaredTypesService {
    String NAMESPACE = "urn:hasor:meteor:soap:MetXmlDeclaredTypesService";

    @MetReturn(name = "result")
    Map<String, MetXmlDeclaredUser> users(@MetParam(name = "request") Map<String, MetXmlDeclaredUser> request);

    @MetReturn(name = "result")
    Map<String, Object> values(@MetParam(name = "request") Map<String, Object> request);

    @MetReturn(name = "result")
    List<Object> list(@MetParam(name = "request") List<Object> request);

    @MetReturn(name = "result")
    Set<Object> set(@MetParam(name = "request") Set<Object> request);

    @MetReturn(name = "result")
    Map<String, List<Map<String, MetXmlDeclaredUser>>> nested( //
            @MetParam(name = "request") Map<String, List<Map<String, MetXmlDeclaredUser>>> request);

    @MetReturn(name = "result")
    Object any(@MetParam(name = "request") Object request);
}
