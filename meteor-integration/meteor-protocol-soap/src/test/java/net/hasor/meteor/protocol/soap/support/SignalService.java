/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.support;
import net.hasor.meteor.MetMessage;

/** 只接收通知、不返回业务结果的 SOAP one-way 示例。 */
public interface SignalService {
    @MetMessage
    void signal(String message);
}
