/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;

/** Hessian 协议错误，业务异常仍通过标准 fault detail 传递原始类型。 */
public final class HessianFaultException extends RuntimeException {
    private final String code;

    public HessianFaultException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return this.code;
    }
}
