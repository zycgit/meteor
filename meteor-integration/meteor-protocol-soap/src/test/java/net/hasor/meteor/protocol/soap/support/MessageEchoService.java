/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.support;

import net.hasor.meteor.MetMessage;

/** 用于验证消息注解与请求响应 WSDL 不一致的接口。 */
public interface MessageEchoService {
    @MetMessage
    String echo(String message);
}
