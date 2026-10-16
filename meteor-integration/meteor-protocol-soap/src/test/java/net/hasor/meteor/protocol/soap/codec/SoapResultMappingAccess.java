/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;
import net.hasor.meteor.protocol.soap.definition.SoapBody;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionAccess;
import net.hasor.meteor.protocol.soap.definition.SoapPart;

/** 为单元测试准备输出 part 并转发结果映射，不包含断言。 */
public final class SoapResultMappingAccess {
    private final SoapResultMapping mapper;

    public SoapResultMappingAccess(Type resultType, String... names) {
        List<SoapPart> parts = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            QName element = new QName("urn:test:result", "output" + index);
            parts.add(SoapDefinitionAccess.part(names[index], element, null, 1, 1));
        }
        SoapBody body = SoapDefinitionAccess.body(null, parts, List.of(), "literal", null);
        this.mapper = new SoapResultMapping(body, resultType, SoapMessageCodec.newXmlMapper());
    }

    public Type[] types() {
        return this.mapper.types();
    }

    public Object[] split(Object result) {
        return this.mapper.split(result);
    }

    public Object join(Object... values) {
        return this.mapper.join(values);
    }
}
