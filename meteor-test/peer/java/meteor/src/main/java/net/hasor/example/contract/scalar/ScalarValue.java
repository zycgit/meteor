/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.io.Serializable;

/** 数值类型保留真实 Java 声明，note 区分 null 与显式空字符串。 */
public class ScalarValue implements Serializable {
    private static final long       serialVersionUID = 1L;
    private              boolean    flag;
    private              long       integer;
    private              float      single;
    private              double     decimal;
    private              ScalarKind kind;
    private              String     note;
    private              String     receipt;

    public boolean isFlag() {
        return this.flag;
    }

    public void setFlag(boolean flag) {
        this.flag = flag;
    }

    public long getInteger() {
        return this.integer;
    }

    public void setInteger(long integer) {
        this.integer = integer;
    }

    public float getSingle() {
        return this.single;
    }

    public void setSingle(float single) {
        this.single = single;
    }

    public double getDecimal() {
        return this.decimal;
    }

    public void setDecimal(double decimal) {
        this.decimal = decimal;
    }

    public ScalarKind getKind() {
        return this.kind;
    }

    public void setKind(ScalarKind kind) {
        this.kind = kind;
    }

    public String getNote() {
        return this.note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }
}
