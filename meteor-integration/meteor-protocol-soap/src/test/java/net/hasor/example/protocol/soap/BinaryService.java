/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 通过直接参数与普通 DTO 交换二进制内容的 Java 服务。 */
public interface BinaryService {
    byte[] echo(byte[] value);

    BinaryPayload echoPayload(BinaryPayload value);
}
