/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;
import java.io.Serializable;
import java.util.List;

/** 第二层对象与有序集合契约，不借助 Object 类型或隐式字符串化传递业务数据。 */
public class ComplexDocument implements Serializable {
    private static final long              serialVersionUID = 1L;
    private              int               documentId;
    private              ComplexCustomer   customer;
    private              List<ComplexLine> lines;
    private              List<String>      route;
    private              String            receipt;

    public int getDocumentId() {
        return this.documentId;
    }

    public void setDocumentId(int documentId) {
        this.documentId = documentId;
    }

    public ComplexCustomer getCustomer() {
        return this.customer;
    }

    public void setCustomer(ComplexCustomer customer) {
        this.customer = customer;
    }

    public List<ComplexLine> getLines() {
        return this.lines;
    }

    public void setLines(List<ComplexLine> lines) {
        this.lines = lines;
    }

    public List<String> getRoute() {
        return this.route;
    }

    public void setRoute(List<String> route) {
        this.route = route;
    }

    public String getReceipt() {
        return this.receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }
}
