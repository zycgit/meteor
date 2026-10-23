/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetField;

/** 模拟 DTO 在真正读取时创建失败，发布时不应执行构造方法。 */
public class FailingNote {
    @MetField(id = 1)
    private String value;

    public FailingNote() {
        throw new IllegalStateException("Cannot construct this note");
    }
}
