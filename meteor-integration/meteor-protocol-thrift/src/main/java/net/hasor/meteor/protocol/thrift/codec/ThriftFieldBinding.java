/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlValue;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.protocol.TField;

/** 字段的线协议定义与本地访问方式；帧数组位置独立于字段编号。 */
final class ThriftFieldBinding {
    final TField     field;
    final byte       requirement;
    final ThriftType type;
    int            position = -1;
    Field          property;
    Method         getter;
    Method         setter;
    TFieldIdEnum   generated;
    ThriftIdlValue defaultValue;

    ThriftFieldBinding(short id, String name, byte requirement, ThriftType type) {
        this.field = new TField(name, type.wireType, id);
        this.requirement = requirement;
        this.type = type;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    Object get(Object owner) throws TException {
        try {
            if (this.position >= 0) {
                return ((Object[]) owner)[this.position];
            }
            if (this.generated != null) {
                TBase value = (TBase) owner;
                if (this.requirement == TFieldRequirementType.OPTIONAL && !value.isSet(this.generated)) {
                    return null;
                }
                return value.getFieldValue(this.generated);
            }
            if (this.getter != null) {
                return this.getter.invoke(owner);
            }
            return this.property.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new TException("Cannot read Thrift field " + this.field.name, failure);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    void set(Object owner, Object value) throws TException {
        try {
            if (this.position >= 0) {
                ((Object[]) owner)[this.position] = value;
            } else if (this.generated != null) {
                ((TBase) owner).setFieldValue(this.generated, value);
            } else if (this.setter != null) {
                this.setter.invoke(owner, value);
            } else {
                this.property.set(owner, value);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            throw new TException("Cannot write Thrift field " + this.field.name, failure);
        }
    }
}
