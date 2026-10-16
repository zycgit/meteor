/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** SOAP 扩展根据普通 Java 对象生成输入、输出的 XML 类型。 */
public interface ProfileService {
    Profile roundTrip(Profile profile);
}
