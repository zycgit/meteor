/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

/** SOAP 定义生成与编解码共享的已校验属性映射，不属于用户配置入口。 */
public final class SoapXmlProperty {
    private final String          javaName;
    private final JavaType        javaType;
    private final QName           name;
    private final boolean         attribute;
    private final boolean         text;
    private final boolean         required;
    private final boolean         nillable;
    private final QName           wrapper;
    private final boolean         wrapperRequired;
    private final boolean         wrapperNillable;
    private final AnnotatedMember reader;
    private final AnnotatedMember writer;

    SoapXmlProperty(String javaName, JavaType javaType, // Java 属性
            QName name, boolean attribute, boolean text, boolean required, boolean nillable, // XML 内容
            QName wrapper, boolean wrapperRequired, boolean wrapperNillable, // 集合包装
            AnnotatedMember reader, AnnotatedMember writer) {
        this.javaName = javaName;
        this.javaType = javaType;
        this.name = name;
        this.attribute = attribute;
        this.text = text;
        this.required = required;
        this.nillable = nillable;
        this.wrapper = wrapper;
        this.wrapperRequired = wrapperRequired;
        this.wrapperNillable = wrapperNillable;
        this.reader = reader;
        this.writer = writer;
        this.reader.fixAccess(true);
        this.writer.fixAccess(true);
    }

    public String getJavaName() {
        return this.javaName;
    }

    public JavaType getJavaType() {
        return this.javaType;
    }

    public QName getName() {
        return this.name;
    }

    public boolean isAttribute() {
        return this.attribute;
    }

    public boolean isText() {
        return this.text;
    }

    public boolean isRequired() {
        return this.required;
    }

    public boolean isNillable() {
        return this.nillable;
    }

    public QName getWrapper() {
        return this.wrapper;
    }

    public boolean isWrapperRequired() {
        return this.wrapperRequired;
    }

    public boolean isWrapperNillable() {
        return this.wrapperNillable;
    }

    public Object read(Object bean) {
        return this.reader.getValue(bean);
    }

    public void write(Object bean, Object value) {
        this.writer.setValue(bean, value);
    }
}
