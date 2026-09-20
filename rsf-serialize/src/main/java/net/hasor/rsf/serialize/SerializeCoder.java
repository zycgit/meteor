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
package net.hasor.rsf.serialize;
import java.io.IOException;

/**
 * 序列化（编码/解码）器。注册到工厂的实现必须支持并发调用。
 * @version : 2014年9月19日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SerializeCoder {
    /**
     * 初始化编码器。工厂在注册前调用；实现不应依赖 RPC 容器。
     * @param classLoader 用于加载业务类型的类加载器
     */
    default void initCoder(ClassLoader classLoader) {
    }

    /**
     * Decode bytes using a non-null target type; null bytes represent a null value.
     * Java serialization restores the type recorded in the object stream.
     */
    public Object decode(byte[] bytes, Class<?> returnType) throws IOException;

    /** Encode Object to byte[] */
    public byte[] encode(Object object) throws IOException;
}
