/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import net.hasor.meteor.annotation.MetMessage;

/** 用于说明标准 Triple 不支持无响应 notification 的发布约束。 */
public interface TripleNotificationService {
    @MetMessage
    void notify(TripleMessage request);
}
