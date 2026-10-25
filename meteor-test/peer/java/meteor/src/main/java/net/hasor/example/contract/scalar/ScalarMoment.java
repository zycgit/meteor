/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.io.Serializable;
import java.util.Date;

/** Java Date 表达绝对时刻；协议中的 epoch 或日期文本映射由 profile 明确声明。 */
public class ScalarMoment implements Serializable {
    private static final long   serialVersionUID = 1L;
    private              Date   instant;
    private              String receipt;

    public Date getInstant() {
        return this.instant;
    }

    public void setInstant(Date instant) {
        this.instant = instant;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }
}
