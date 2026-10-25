/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 通过发布时的 asMessage 配置消息调用的旧式接口。 */
public interface PlainSignalService {
    void signal(String message);
}
