/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.meteor.annotation.MetField;

import java.io.Serializable;

/** 查询条件作为普通对象参数，不依赖任意协议专用类型。 */
public class OrderQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    @MetField(id = 1)
    private int id;

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
