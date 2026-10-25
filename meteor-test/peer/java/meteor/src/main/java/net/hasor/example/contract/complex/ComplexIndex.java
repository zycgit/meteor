/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;
import java.io.Serializable;
import java.util.Map;

/** Map key 使用合法 XML localName，值分别声明为业务 DTO 和 int32。 */
public class ComplexIndex implements Serializable {
    private static final long                     serialVersionUID = 1L;
    private              int                      revision;
    private              Map<String, ComplexLine> linesByCode;
    private              Map<String, Integer>     stockByCode;
    private              String                   receipt;

    public int getRevision() {
        return this.revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Map<String, ComplexLine> getLinesByCode() {
        return this.linesByCode;
    }

    public void setLinesByCode(Map<String, ComplexLine> linesByCode) {
        this.linesByCode = linesByCode;
    }

    public Map<String, Integer> getStockByCode() {
        return this.stockByCode;
    }

    public void setStockByCode(Map<String, Integer> stockByCode) {
        this.stockByCode = stockByCode;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }
}
