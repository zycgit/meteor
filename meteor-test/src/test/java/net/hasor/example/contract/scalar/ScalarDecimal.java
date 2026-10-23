/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.io.Serializable;
import java.math.BigDecimal;

/** 任意精度小数与二进制浮点分开，不能将 amount 偷换成 double。 */
public class ScalarDecimal implements Serializable {
    private static final long       serialVersionUID = 1L;
    private              BigDecimal amount;
    private              String     receipt;

    public BigDecimal getAmount() {
        return this.amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }
}
