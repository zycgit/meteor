/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.support.example;

/** 最小的普通接口，用于 HTTP 挂载示例。 */
public interface TextService {
    String echo(String value);
}
