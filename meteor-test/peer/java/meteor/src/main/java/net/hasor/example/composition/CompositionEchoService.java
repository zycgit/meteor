/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition;

/** 多个协议共享普通 Java 服务契约，插件负责各自的线协议。 */
public interface CompositionEchoService {
    String echo(String value);
}
