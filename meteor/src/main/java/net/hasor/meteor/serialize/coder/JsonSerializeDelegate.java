/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize.coder;
import java.io.IOException;

/**
 * JSON 序列化的用户委托，不依赖 Meteor 内置的 JSON 实现。
 * 实现应支持并发调用，编码与解码使用 UTF-8，生命周期由提供者管理。
 */
public interface JsonSerializeDelegate {
    byte[] encode(Object value) throws IOException;

    Object decode(byte[] json, Class<?> type) throws IOException;
}
