/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import java.util.Map;

/** 将一个 SOAP 多 part 输出作为一个 Java Map 返回。 */
public interface MultiPartService {
    Map<String, String> describe(String name);
}
