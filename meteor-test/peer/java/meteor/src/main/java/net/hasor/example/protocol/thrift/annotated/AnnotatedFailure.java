/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import net.hasor.meteor.annotation.MetField;

/** 普通异常不依赖 TException 或官方生成 DTO。 */
public class AnnotatedFailure extends Exception {
    @MetField(id = 1)
    private int    code;
    @MetField(id = 2)
    private String reason;

    public AnnotatedFailure() {
    }

    public int getCode() {
        return this.code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getReason() {
        return this.reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
