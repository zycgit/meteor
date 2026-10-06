/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize.coder;
import java.io.*;
import java.util.Objects;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;

/** Java object stream serialization. Values must implement Serializable. */
public class JavaSerializeCoder implements SerializeCoder {
    private volatile ClassLoader classLoader = SerializeFactory.defaultClassLoader();

    @Override
    public String name() {
        return "Java";
    }

    @Override
    public void initCoder(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    @Override
    public byte[] encode(Object object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(object);
        }
        return bytes.toByteArray();
    }

    @Override
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException {
        Objects.requireNonNull(returnType, "returnType");
        if (bytes == null) {
            return null;
        }
        final ClassLoader loader = this.classLoader;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                try {
                    return Class.forName(desc.getName(), false, loader);
                } catch (ClassNotFoundException e) {
                    return super.resolveClass(desc);
                }
            }
        }) {
            return input.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot resolve serialized Java type", e);
        }
    }
}
