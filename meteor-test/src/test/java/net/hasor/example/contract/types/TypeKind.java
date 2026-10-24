/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import net.hasor.meteor.annotation.MetEnumValue;

/** 枚举符号和契约编号固定，各语言使用相同的 FIRST/SECOND 业务含义。 */
public enum TypeKind {
    @MetEnumValue(id = 0) FIRST,
    @MetEnumValue(id = 1) SECOND
}
