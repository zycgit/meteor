/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hprose;
import net.hasor.meteor.annotation.MetMethod;

/** Java 方法可以重命名，线上方法身份保持为 echo。 */
public interface NamedEcho {
    @MetMethod(name = "echo")
    String repeat(String value);
}
