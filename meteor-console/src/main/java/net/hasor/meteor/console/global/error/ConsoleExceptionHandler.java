/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.error;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.global.auth.AuthenticationException;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.web.Invoker;

/** 将边界、业务及存储异常统一转换成 HTTP JSON 错误。 */
public final class ConsoleExceptionHandler {
    public void handle(Invoker invoker, Throwable failure) throws Exception {
        Throwable visible = failure;
        while ((visible instanceof InvocationTargetException || visible instanceof UndeclaredThrowableException || visible instanceof ExecutionException || visible instanceof CompletionException) && visible.getCause() != null) {
            visible = visible.getCause();
        }
        int status = status(visible);
        String message = visible.getMessage();
        if (StringUtils.isBlank(message)) {
            message = visible.getClass().getSimpleName();
        }

        invoker.getHttpResponse().setStatus(status);
        invoker.getHttpResponse().setContentType("application/json;charset=UTF-8");
        JsonUtils.writeValue(invoker.getHttpResponse().getOutputStream(), Map.of("error", message, "status", status));
        invoker.setSkipRender();
    }

    private static int status(Throwable failure) {
        if (failure instanceof ConsoleOperationException && failure.getCause() != null) {
            return status(failure.getCause());
        }
        if (failure instanceof ConsoleHttpException http) {
            return http.status();
        }
        if (failure instanceof AuthenticationException) {
            return 401;
        }
        if (failure instanceof SecurityException) {
            return 403;
        }
        if (failure instanceof NoSuchElementException) {
            return 404;
        }
        if (failure instanceof IllegalArgumentException || failure instanceof JsonProcessingException) {
            return 400;
        }
        if (failure instanceof UnsupportedOperationException || failure instanceof ConcurrentModificationException) {
            return 409;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MetException remote) {
                int status = remote.getStatus();
                if (status == ProtocolStatus.Unauthorized) {
                    return 403;
                }
                if (status == ProtocolStatus.Timeout) {
                    return 504;
                }
                if (status == 400 || status == 403 || status == 404 || status == 409) {
                    return status;
                }
            }
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
                return 504;
            }
        }
        return 502;
    }
}
