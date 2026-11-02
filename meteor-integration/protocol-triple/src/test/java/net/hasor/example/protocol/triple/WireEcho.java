/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;

/** 对端只声明线上契约的原始方法名，不依赖公共名称注解。 */
public interface WireEcho {
    String echo(String value);
}