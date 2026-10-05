/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize.coder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.SerializerFactory;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.SerializeFactory;

/** Hessian 1 object encoding, compatible with the original RSF coder. */
public class HessianSerializeCoder implements SerializeCoder {
    private volatile SerializerFactory serializerFactory = new SerializerFactory(SerializeFactory.defaultClassLoader());

    @Override
    public String name() {
        return "Hessian";
    }

    @Override
    public void initCoder(ClassLoader classLoader) {
        this.serializerFactory = new SerializerFactory(Objects.requireNonNull(classLoader, "classLoader"));
    }

    @Override
    public byte[] encode(Object object) throws IOException {
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(binary);
        output.setSerializerFactory(this.serializerFactory);
        try {
            output.writeObject(object);
            output.flush();
            return binary.toByteArray();
        } catch (RuntimeException e) {
            throw new IOException("Cannot encode Hessian value", e);
        } finally {
            output.close();
        }
    }

    @Override
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException {
        Objects.requireNonNull(returnType, "returnType");
        if (bytes == null) {
            return null;
        }
        HessianInput input = new HessianInput(new ByteArrayInputStream(bytes));
        input.setSerializerFactory(this.serializerFactory);
        try {
            return input.readObject(returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode Hessian value", e);
        } finally {
            input.close();
        }
    }
}
