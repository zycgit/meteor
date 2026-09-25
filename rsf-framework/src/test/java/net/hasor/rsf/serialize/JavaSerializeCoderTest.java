/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize;

import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;

import java.io.*;

import static org.junit.Assert.*;

public class JavaSerializeCoderTest {
    @Test(expected = NotSerializableException.class)
    public void nonSerializableValueFails() throws Exception {
        new JavaSerializeCoder().encode(new Object());
    }

    @Test
    public void businessClassLoaderIsUsedToResolveValues() throws Exception {
        final String name = Model.class.getName();
        ClassLoader isolated = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String className) throws ClassNotFoundException {
                if (!className.equals(name)) {
                    throw new ClassNotFoundException(className);
                }
                try (InputStream in = Model.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int size;
                    while ((size = in.read(buffer)) != -1) {
                        bytes.write(buffer, 0, size);
                    }
                    byte[] bytecode = bytes.toByteArray();
                    return defineClass(className, bytecode, 0, bytecode.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(className, e);
                }
            }
        };
        JavaSerializeCoder coder = new JavaSerializeCoder();
        byte[] bytes = coder.encode(new Model());
        coder.initCoder(isolated);
        Class<?> isolatedType = isolated.loadClass(name);
        Object decoded = coder.decode(bytes, isolatedType);
        assertSame(isolatedType, decoded.getClass());
        assertSame(isolated, decoded.getClass().getClassLoader());
        assertEquals("business value", isolatedType.getField("value").get(decoded));
    }

    @Test
    public void preservesCyclesAndSharedReferences() throws Exception {
        JavaSerializeCoder coder = new JavaSerializeCoder();
        Object[] value = new Object[2];
        value[0] = value;
        value[1] = value;
        Object[] result = (Object[]) coder.decode(coder.encode(value), Object[].class);
        assertSame(result, result[0]);
        assertSame(result, result[1]);
    }

    public static class Model implements Serializable {
        private static final long serialVersionUID = 1L;
        public String value = "business value";
    }
}
