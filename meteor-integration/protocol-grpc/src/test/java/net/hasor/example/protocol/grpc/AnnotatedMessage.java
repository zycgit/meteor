/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** 字段号与已有 Message proto 相同，Java 类型独立于生成代码。 */
@MetType(name = "Message")
public class AnnotatedMessage {
    @MetField(id = 7)
    private String value;
    @MetField(id = 2)
    private int    count;

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getCount() {
        return this.count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
