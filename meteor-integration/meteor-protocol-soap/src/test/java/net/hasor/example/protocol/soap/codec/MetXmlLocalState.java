/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetType;

/** 枚举类型名称固定，类型命名空间沿用服务契约。 */
@MetType(name = "LocalState")
public enum MetXmlLocalState {
    @MetEnumValue(name = "ready-to-send") READY
}
