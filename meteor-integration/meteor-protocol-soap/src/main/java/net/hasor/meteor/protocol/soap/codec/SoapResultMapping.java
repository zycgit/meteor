/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapBody;
import net.hasor.meteor.protocol.soap.definition.SoapPart;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;

/** 将多 part 的输出消息与一个 Java 返回对象对应，保留各部分的实际类型和对象引用。 */
final class SoapResultMapping {
    private final SoapBody                     binding;
    private final JavaType                     resultType;
    private final Type[]                       types;
    private final XmlMapper                    mapper;
    private final SoapXmlMapping               mapping;
    private final List<BeanPropertyDefinition> readableProperties;
    private final List<BeanPropertyDefinition> writableProperties;

    SoapResultMapping(SoapBody binding, Type resultType, SoapXmlMapping mapping) {
        this.binding = binding;
        this.mapper = mapping.bindings();
        this.mapping = mapping;
        this.resultType = this.mapper.constructType(resultType);
        BeanDescription readable = this.mapper.getSerializationConfig().introspect(this.resultType);
        BeanDescription writable = this.mapper.getDeserializationConfig().introspect(this.resultType);
        this.readableProperties = readable.findProperties();
        this.writableProperties = writable.findProperties();
        this.types = new Type[binding.parts().size()];
        for (int index = 0; index < this.types.length; index++) {
            if (this.types.length == 1) {
                this.types[index] = resultType;
            } else if (this.resultType.isMapLikeType()) {
                Class<?> keyType = this.resultType.getKeyType().getRawClass();
                if (!keyType.isAssignableFrom(String.class)) {
                    throw new UnsupportedOperationException("SOAP output Map keys must accept String part names");
                }
                this.types[index] = this.resultType.getContentType();
            } else {
                SoapPart part = binding.parts().get(index);
                String name = part.name();
                BeanPropertyDefinition property = this.property(this.writableProperties, part);
                if (property == null) {
                    property = this.property(this.readableProperties, part);
                }
                if (property == null) {
                    throw new UnsupportedOperationException("SOAP output part has no Java result property: " + name);
                }
                this.types[index] = property.getPrimaryType();
            }
        }
    }

    Type[] types() {
        return this.types.clone();
    }

    Object[] split(Object result) {
        Object[] values = new Object[this.types.length];
        for (int index = 0; index < values.length; index++) {
            SoapPart part = this.binding.parts().get(index);
            String name = part.name();
            if (values.length == 1) {
                values[index] = result;
            } else if (result instanceof Map<?, ?> map) {
                values[index] = map.get(name);
            } else if (result != null) {
                BeanPropertyDefinition property = this.property(this.readableProperties, part);
                AnnotatedMember accessor = property == null ? null : property.getAccessor();
                if (accessor == null) {
                    throw new IllegalArgumentException("SOAP output part has no readable property: " + name);
                }
                accessor.fixAccess(true);
                values[index] = accessor.getValue(result);
            }
        }
        return values;
    }

    Object join(Object[] values) {
        if (values.length != this.types.length) {
            throw new IllegalArgumentException("SOAP output part and Java value counts do not match");
        }
        if (values.length == 0) {
            return null;
        }
        if (values.length == 1) {
            return values[0];
        }

        // 多个输出 part 组成一个返回 DTO 或 Map，不通过二次序列化复制共享引用。
        Object result;
        if (this.resultType.isMapLikeType()) {
            result = this.mapper.convertValue(Collections.emptyMap(), this.resultType);
        } else {
            result = ClassUtils.newInstance(this.resultType.getRawClass());
        }

        for (int index = 0; index < values.length; index++) {
            SoapPart part = this.binding.parts().get(index);
            this.assign(result, part, values[index]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void assign(Object result, SoapPart part, Object value) {
        String name = part.name();
        if (result instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(name, value);
            return;
        }

        BeanPropertyDefinition property = this.property(this.writableProperties, part);
        AnnotatedMember mutator = property == null ? null : property.getNonConstructorMutator();
        if (mutator == null) {
            throw new IllegalArgumentException("SOAP output part has no writable property: " + name);
        }
        mutator.fixAccess(true);
        mutator.setValue(result, value);
    }

    private BeanPropertyDefinition property(List<BeanPropertyDefinition> properties, SoapPart part) {
        // 文档 part 先按完整元素 QName 定位，允许结果对象中出现同名、不同命名空间的属性。
        BeanPropertyDefinition selected = null;
        for (BeanPropertyDefinition property : properties) {
            if (part.element().equals(this.mapping.propertyName(property))) {
                if (selected != null) {
                    throw new IllegalArgumentException("Ambiguous SOAP output part property: " + part.name());
                }
                selected = property;
            }
        }
        if (selected != null) {
            return this.requireElement(selected);
        }

        // RPC part 的消息名可以独立于 XML 元素名，保留原有按 part 名聚合结果的约定。
        for (BeanPropertyDefinition property : properties) {
            QName xmlName = this.mapping.propertyName(property);
            boolean xmlMatched = StringUtils.equals(part.name(), xmlName.getLocalPart());
            boolean javaMatched = StringUtils.equals(part.name(), property.getName());
            boolean matched = xmlMatched || javaMatched;
            if (matched) {
                if (selected != null) {
                    throw new IllegalArgumentException("Ambiguous SOAP output part property: " + part.name());
                }
                selected = property;
            }
        }
        return this.requireElement(selected);
    }

    private BeanPropertyDefinition requireElement(BeanPropertyDefinition property) {
        if (property == null) {
            return null;
        }

        String name = property.getInternalName();
        // 聚合对象不会生成 XML 元素；只检查实际被选为 part 的属性，并合并两个方向的成员声明。
        if (this.hasAttribute(this.readableProperties, name) || this.hasAttribute(this.writableProperties, name)) {
            String member = this.resultType.toCanonical() + "." + name;
            throw new IllegalArgumentException("XML attribute has no parent element for SOAP output part: " + member);
        }
        return property;
    }

    private boolean hasAttribute(List<BeanPropertyDefinition> properties, String name) {
        for (BeanPropertyDefinition property : properties) {
            if (!StringUtils.equals(name, property.getInternalName())) {
                continue;
            }

            AnnotatedMember[] members = { property.getField(), property.getGetter(), property.getSetter() };
            for (AnnotatedMember member : members) {
                JacksonXmlProperty declaration = member == null ? null : member.getAnnotation(JacksonXmlProperty.class);
                if (declaration != null && declaration.isAttribute()) {
                    return true;
                }
            }
        }
        return false;
    }
}