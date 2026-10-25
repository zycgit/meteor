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

/** 多协议共用的订单数据；公共注解固定协议字段编号，可空引用按协议映射。 */
public class Order implements Serializable {
    private static final long serialVersionUID = 1L;

    @MetField(id = 1)
    private int id;

    @MetField(id = 2)
    private String description;

    @MetField(id = 3)
    private byte[] attachment;

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public byte[] getAttachment() {
        return this.attachment;
    }

    public void setAttachment(byte[] attachment) {
        this.attachment = attachment;
    }
}
