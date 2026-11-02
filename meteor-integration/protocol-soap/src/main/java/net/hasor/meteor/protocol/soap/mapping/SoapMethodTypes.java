/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;

/** SOAP 内部的方法泛型解析，跨包供契约生成、协议绑定校验和消息读写共用。 */
public final class SoapMethodTypes {
    private final JavaType[] parameterTypes;
    private final JavaType   returnType;

    public SoapMethodTypes(XmlMapper mapper, Class<?> serviceType, Method method) {
        requireOperationValue(method);
        for (AnnotatedElement parameter : method.getParameters()) {
            requireOperationValue(parameter);
        }
        TypeFactory factory = mapper.getTypeFactory();
        MethodMeta description = ServiceMetaParser.method(serviceType, method);
        this.parameterTypes = ServiceMetaParser.parameterTypes(description, factory);
        this.returnType = ServiceMetaParser.returnType(description, factory);
    }

    private static void requireOperationValue(AnnotatedElement member) {
        JacksonXmlElementWrapper wrapper = member.getAnnotation(JacksonXmlElementWrapper.class);
        if (wrapper != null && wrapper.useWrapping()) {
            throw new IllegalArgumentException("SOAP element wrapping is only supported on DTO properties");
        }
        JacksonXmlProperty property = member.getAnnotation(JacksonXmlProperty.class);
        if (property != null && property.isAttribute()) {
            String location = member.toString();
            if (member instanceof Method method) {
                location = method.getDeclaringClass().getName() + "#" + method.getName();
            }
            throw new IllegalArgumentException("XML attribute has no parent element on SOAP operation: " + location);
        }
    }

    public JavaType[] getParameterTypes() {
        return this.parameterTypes.clone();
    }

    public JavaType getReturnType() {
        return this.returnType;
    }
}
