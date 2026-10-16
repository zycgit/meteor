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
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.definition.SoapBody;

/** 将多 part 的输出消息与一个 Java 返回对象对应，保留各部分的实际类型和对象引用。 */
final class SoapResultMapping {
    private final SoapBody                     binding;
    private final JavaType                     resultType;
    private final Type[]                       types;
    private final XmlMapper                    mapper;
    private final List<BeanPropertyDefinition> readableProperties;
    private final List<BeanPropertyDefinition> writableProperties;

    SoapResultMapping(SoapBody binding, Type resultType, XmlMapper mapper) {
        this.binding = binding;
        this.mapper = mapper;
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
                    throw new IllegalArgumentException("SOAP output Map keys must accept String part names");
                }
                this.types[index] = this.resultType.getContentType();
            } else {
                String name = binding.parts().get(index).name();
                BeanPropertyDefinition property = this.property(this.writableProperties, name);
                if (property == null) {
                    property = this.property(this.readableProperties, name);
                }
                if (property == null) {
                    throw new IllegalArgumentException("SOAP output part has no Java result property: " + name);
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
            String name = this.binding.parts().get(index).name();
            if (values.length == 1) {
                values[index] = result;
            } else if (result instanceof Map<?, ?> map) {
                values[index] = map.get(name);
            } else if (result != null) {
                BeanPropertyDefinition property = this.property(this.readableProperties, name);
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
            String name = this.binding.parts().get(index).name();
            this.assign(result, name, values[index]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void assign(Object result, String name, Object value) {
        if (result instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(name, value);
            return;
        }
        BeanPropertyDefinition property = this.property(this.writableProperties, name);
        AnnotatedMember mutator = property == null ? null : property.getNonConstructorMutator();
        if (mutator == null) {
            throw new IllegalArgumentException("SOAP output part has no writable property: " + name);
        }
        mutator.fixAccess(true);
        mutator.setValue(result, value);
    }

    private BeanPropertyDefinition property(List<BeanPropertyDefinition> properties, String name) {
        for (BeanPropertyDefinition property : properties) {
            if (StringUtils.equals(name, property.getName())) {
                return property;
            }
        }
        return null;
    }
}
