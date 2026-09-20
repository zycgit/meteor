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
import com.alibaba.fastjson.JSON;
import net.hasor.rsf.serialize.SerializeCoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** JSON serialization with UTF-8 on both sides of the wire. */
public class JsonSerializeCoder implements SerializeCoder {
    @Override
    public byte[] encode(Object object) throws IOException {
        try {
            return JSON.toJSONString(object).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IOException("Cannot encode JSON value", e);
        }
    }

    @Override
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException {
        Objects.requireNonNull(returnType, "returnType");
        if (bytes == null) {
            return null;
        }
        try {
            return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), returnType);
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode JSON value", e);
        }
    }
}
