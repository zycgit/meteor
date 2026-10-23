/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;

/** 一个 Java 方法与 CALL/REPLY 正文之间的静态映射。 */
final class ThriftMethodBinding {
    final Method     method;
    final String     name;
    final boolean    oneway;
    final ThriftType args;
    final ThriftType result;

    ThriftMethodBinding(Method method, String name, boolean oneway, ThriftType args, ThriftType result) {
        this.method = method;
        this.name = name;
        this.oneway = oneway;
        this.args = args;
        this.result = result;
    }

    Object arguments(List<Object> parameters) throws TException {
        if (parameters.size() != this.args.fields.size()) {
            throw new TApplicationException(TApplicationException.PROTOCOL_ERROR, "Thrift argument count differs");
        }
        Object value = this.args.newValue();
        for (int index = 0; index < parameters.size(); index++) {
            this.args.fields.get(index).set(value, parameters.get(index));
        }
        return value;
    }

    static Object binary(Object value, Class<?> target) {
        if (value instanceof byte[] bytes && target == ByteBuffer.class) {
            return ByteBuffer.wrap(bytes);
        }
        if (value instanceof ByteBuffer buffer && target == byte[].class) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            return bytes;
        }
        return value;
    }
}
