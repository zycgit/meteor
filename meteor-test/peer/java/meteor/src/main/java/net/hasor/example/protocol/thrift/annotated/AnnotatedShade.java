/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import net.hasor.meteor.annotation.MetEnumValue;

/** 顺序与官方枚举不同，协议值保持不变。 */
public enum AnnotatedShade {
    @MetEnumValue(id = 2) BLUE,
    @MetEnumValue(id = 0) UNKNOWN,
    @MetEnumValue(id = 1) RED
}
