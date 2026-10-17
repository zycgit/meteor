/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.util.List;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor;

/** 一个 Java 类型对应的 SOAP XML 映射，由共享映射器创建。 */
public final class SoapXmlType {
    private final JavaType              javaType;
    private final String                name;
    private final String                namespace;
    private final List<SoapXmlProperty> properties;
    private final AnnotatedConstructor  constructor;

    SoapXmlType(JavaType javaType, String name, String namespace, // 类型名称
            List<SoapXmlProperty> properties, AnnotatedConstructor constructor) {
        this.javaType = javaType;
        this.name = name;
        this.namespace = namespace;
        this.properties = List.copyOf(properties);
        this.constructor = constructor;
        if (this.constructor != null) {
            this.constructor.fixAccess(true);
        }
    }

    public JavaType getJavaType() {
        return this.javaType;
    }

    public String getName() {
        return this.name;
    }

    public String getNamespace() {
        return this.namespace;
    }

    public List<SoapXmlProperty> getProperties() {
        return this.properties;
    }

    public Object newInstance() {
        if (this.constructor == null) {
            throw new IllegalArgumentException("SOAP XML type is not an ordinary POJO: " + this.javaType);
        }
        try {
            return this.constructor.call();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Unable to create SOAP XML value: " + this.javaType, failure);
        }
    }
}
