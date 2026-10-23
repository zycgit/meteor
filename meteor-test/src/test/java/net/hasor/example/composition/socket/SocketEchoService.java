/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.socket;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** 同一个 Java 契约可按 RSF、Dubbo 或 ONC RPC 显式发布。 */
@MetService(id = 0x31234579L)
public interface SocketEchoService {
    @MetMethod(id = 1)
    String echo(String value);
}
