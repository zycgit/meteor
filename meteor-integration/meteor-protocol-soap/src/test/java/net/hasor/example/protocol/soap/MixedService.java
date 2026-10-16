/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

import net.hasor.meteor.MetMessage;

/** 同一 SOAP 服务分别声明普通调用与消息调用。 */
public interface MixedService {
    String echo(String message);

    @MetMessage
    void signal(String message);
}
