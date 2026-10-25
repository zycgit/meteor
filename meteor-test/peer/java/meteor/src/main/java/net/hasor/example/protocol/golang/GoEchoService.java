/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.golang;
import net.hasor.meteor.annotation.MetMethod;

/** Go SDK 导出方法使用首字母大写，Java 仍沿用普通 echo 方法名。 */
public interface GoEchoService {
    @MetMethod(name = "Echo")
    String echo(String value);
}
