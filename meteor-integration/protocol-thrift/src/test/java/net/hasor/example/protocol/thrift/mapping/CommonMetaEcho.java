/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;

/** 相同的普通接口可生成契约，也可映射已有 IDL。 */
public interface CommonMetaEcho {
    @MetMethod(name = "Echo")
    Note mirror(@MetParam(name = "value", id = 7) Note note);
}
