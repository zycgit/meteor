/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import net.hasor.meteor.domain.MetRuntimeUtils;

/** 只执行已经编译的位置映射，不在收发时扫描注解或发现属性。 */
public final class XdrStructCodec implements XdrCodec<Object> {
    public final  List<XdrPropertyBinding> fields = new ArrayList<>();
    public        String                   name;
    private final Constructor<?>           constructor;

    public XdrStructCodec(Class<?> type) {
        this.name = MetRuntimeUtils.getProtocolTypeName(type);
        try {
            this.constructor = type.getDeclaredConstructor();
            if (!this.constructor.trySetAccessible()) {
                throw new IllegalArgumentException("Inaccessible XDR constructor: " + type.getName());
            }
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("XDR DTO requires a no-arg constructor: " + type.getName(), failure);
        }
    }

    @Override
    public Object read(XdrInput input) {
        try {
            Object value = this.constructor.newInstance();
            for (XdrPropertyBinding field : this.fields) {
                field.set(value, input.read(field.codec));
            }
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot decode XDR DTO", failure);
        }
    }

    @Override
    public void write(XdrOutput output, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("XDR struct cannot be null; optional values require a contract");
        }

        try {
            for (XdrPropertyBinding field : this.fields) {
                output.write(field.codec, field.get(value));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot encode XDR DTO", failure);
        }
    }
}