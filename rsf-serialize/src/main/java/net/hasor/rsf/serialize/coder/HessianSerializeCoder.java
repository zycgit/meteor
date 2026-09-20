/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.serialize.coder;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.SerializerFactory;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.SerializeFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Hessian 1 object encoding, compatible with the original RSF coder. */
public class HessianSerializeCoder implements SerializeCoder {
    private volatile SerializerFactory serializerFactory = new SerializerFactory(SerializeFactory.defaultClassLoader());

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
