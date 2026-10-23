/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.logging;
import net.hasor.cobble.logging.Logger;

/** 只记录本次适配调用，不改变进程共享的日志工厂。 */
final class RecordingCobbleLogger implements Logger {
    private boolean   traceEnabled = true;
    private boolean   debugEnabled = true;
    private String    level;
    private String    message;
    private Throwable failure;

    @Override
    public boolean isDebugEnabled() {
        return this.debugEnabled;
    }

    @Override
    public boolean isTraceEnabled() {
        return this.traceEnabled;
    }

    @Override
    public void error(String message, Throwable failure) {
        this.record("error", message, failure);
    }

    @Override
    public void error(String message) {
        this.record("error", message, null);
    }

    @Override
    public void debug(String message) {
        this.record("debug", message, null);
    }

    @Override
    public void info(String message) {
        this.record("info", message, null);
    }

    @Override
    public void trace(String message) {
        this.record("trace", message, null);
    }

    @Override
    public void warn(String message) {
        this.record("warn", message, null);
    }

    @Override
    public void warn(String message, Throwable failure) {
        this.record("warn", message, failure);
    }

    void setTraceEnabled(boolean enabled) {
        this.traceEnabled = enabled;
    }

    void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    String getLevel() {
        return this.level;
    }

    String getMessage() {
        return this.message;
    }

    Throwable getFailure() {
        return this.failure;
    }

    private void record(String level, String message, Throwable failure) {
        this.level = level;
        this.message = message;
        this.failure = failure;
    }
}
