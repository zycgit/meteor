/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.error;
/** 带有明确 HTTP 状态的控制台输入错误。 */
public final class ConsoleHttpException extends RuntimeException {
    private final int status;

    public ConsoleHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return this.status;
    }
}