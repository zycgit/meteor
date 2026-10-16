/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** EchoService 的最小示例实现。 */
public final class EchoServiceImpl implements EchoService {
    @Override
    public String echo(String message) {
        return message;
    }
}
