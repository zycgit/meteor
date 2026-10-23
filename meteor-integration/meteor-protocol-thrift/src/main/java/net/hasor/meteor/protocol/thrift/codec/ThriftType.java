/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetRuntimeUtils;
import org.apache.thrift.TException;

/** 发布时编译的值类型；递归类型先登记后填充，发布后不再修改。 */
final class ThriftType {
    final byte     wireType;
    final Class<?> javaType;
    String name;
    final List<ThriftFieldBinding>       fields      = new ArrayList<>();
    final Map<Short, ThriftFieldBinding> fieldsById  = new LinkedHashMap<>();
    final Map<Integer, Object>           enumValues  = new LinkedHashMap<>();
    final Map<Object, Integer>           enumNumbers = new LinkedHashMap<>();
    final Map<String, Object>            enumNames   = new LinkedHashMap<>();
    Constructor<?> constructor;
    ThriftType     element;
    ThriftType     key;
    boolean        binary;
    boolean        union;

    ThriftType(byte wireType, Class<?> javaType) {
        this.wireType = wireType;
        this.javaType = javaType;
        this.name = MetRuntimeUtils.getProtocolTypeName(javaType);
    }

    void add(ThriftFieldBinding binding) {
        if (this.fieldsById.putIfAbsent(binding.field.id, binding) != null) {
            throw new IllegalArgumentException("Duplicate Thrift field id: " + binding.field.id);
        }
        for (ThriftFieldBinding field : this.fields) {
            if (StringUtils.equals(field.field.name, binding.field.name)) {
                throw new IllegalArgumentException("Duplicate Thrift field name: " + binding.field.name);
            }
        }
        this.fields.add(binding);
    }

    Object newValue() throws TException {
        if (this.javaType == Object[].class) {
            return new Object[this.fields.size()];
        }
        try {
            return this.constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new TException("Cannot create Thrift value " + this.javaType.getName(), failure);
        }
    }
}
