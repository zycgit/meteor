/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.error;
/** 保留底层异常类型，同时对外提供脱敏或批量操作进度信息。 */
public final class ConsoleOperationException extends RuntimeException {
    public ConsoleOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
