/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetField;

/** getter 声明映射，字段通过普通 setter 接收反序列化结果。 */
public class MappedBean {
    private String value;

    public MappedBean() {
    }

    @MetField(id = 1)
    public String getValue() { return this.value; }
    public void setValue(String value) { this.value = value; }
}
