/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetEnumValue;

public enum MappedState {
    @MetEnumValue(id = 99) LAST,
    @MetEnumValue(id = 7) FIRST
}
