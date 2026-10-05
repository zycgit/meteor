/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize;
import java.io.IOException;

/**
 * 序列化（编码/解码）器。注册到工厂的实现必须支持并发调用。
 * @version : 2014年9月19日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SerializeCoder {
    /** SPI 注册名称，匹配时不区分大小写，不能为空或空白。 */
    String name();

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
    Object decode(byte[] bytes, Class<?> returnType) throws IOException;

    /** Encode Object to byte[] */
    byte[] encode(Object object) throws IOException;
}
