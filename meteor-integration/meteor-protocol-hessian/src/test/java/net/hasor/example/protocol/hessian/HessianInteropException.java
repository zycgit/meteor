/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;

/** 声明在业务接口上的受检异常，由 Hessian fault detail 传递。 */
public class HessianInteropException extends Exception {
    private static final long serialVersionUID = 1L;

    public HessianInteropException(String message) {
        super(message);
    }
}
