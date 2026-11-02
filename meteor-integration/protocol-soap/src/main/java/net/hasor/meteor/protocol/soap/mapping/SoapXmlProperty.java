/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.util.List;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

/** SOAP 定义生成与编解码共享的已校验属性映射，不属于用户配置入口。 */
public final class SoapXmlProperty {
    private final String          javaName;
    private final String          bindingName;
    private final List<String>    aliases;
    private final boolean         jacksonValue;
    private final boolean         jacksonContent;
    private final JavaType        javaType;
    private final int             order;
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
    private final AnnotatedMember bindingMember;

    SoapXmlProperty(String javaName, String bindingName, List<String> aliases, // Jackson 绑定名称
            JavaType javaType, boolean jacksonValue, boolean jacksonContent, int order, // Java 属性及顺序
            QName name, boolean attribute, boolean text, boolean required, boolean nillable, // XML 内容
            QName wrapper, boolean wrapperRequired, boolean wrapperNillable, // 元素包装
            AnnotatedMember reader, AnnotatedMember writer, AnnotatedMember bindingMember) {
        this.javaName = javaName;
        this.bindingName = bindingName;
        this.aliases = List.copyOf(aliases);
        this.jacksonValue = jacksonValue;
        this.jacksonContent = jacksonContent;
        this.javaType = javaType;
        this.order = order;
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
        this.bindingMember = bindingMember;
        if (this.reader != null) {
            this.reader.fixAccess(true);
        }
        if (this.writer != null) {
            this.writer.fixAccess(true);
        }
    }

    public String getJavaName() {
        return this.javaName;
    }

    public String getBindingName() {
        return this.bindingName;
    }

    public List<String> getAliases() {
        return this.aliases;
    }

    public boolean isJacksonValue() {
        return this.jacksonValue;
    }

    public boolean isJacksonContent() {
        return this.jacksonContent;
    }

    public boolean isReadable() {
        return this.reader != null;
    }

    public boolean isWritable() {
        return this.writer != null;
    }

    public AnnotatedMember getBindingMember() {
        return this.bindingMember;
    }

    public JavaType getJavaType() {
        return this.javaType;
    }

    public int getOrder() {
        return this.order;
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
        if (this.reader == null) {
            throw new IllegalArgumentException("SOAP encoded property is not readable: " + this.javaName);
        }
        return this.reader.getValue(bean);
    }

    public void write(Object bean, Object value) {
        if (this.writer == null) {
            throw new IllegalArgumentException("SOAP encoded property requires Jackson binding: " + this.javaName);
        }
        this.writer.setValue(bean, value);
    }
}
