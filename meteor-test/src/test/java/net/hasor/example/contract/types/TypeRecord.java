/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.io.Serializable;
import net.hasor.meteor.annotation.MetField;

/** 最小嵌套结构，用于确认结构对象没有退化成文本或动态 Map。 */
public class TypeRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    @MetField(id = 1)
    private int index;

    @MetField(id = 2)
    private String name;

    public int getIndex() {
        return this.index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
