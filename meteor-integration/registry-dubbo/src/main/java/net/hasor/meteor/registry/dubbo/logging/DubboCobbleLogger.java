/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.logging;
import java.util.function.Supplier;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.logging.Logger;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.Level;
import org.apache.dubbo.common.logger.helpers.FormattingTuple;
import org.apache.dubbo.common.logger.helpers.MessageFormatter;

/** 将 Dubbo 日志方法与占位参数转换为 Cobble 日志调用。 */
final class DubboCobbleLogger implements ErrorTypeAwareLogger {
    private final Logger          logger;
    private final Supplier<Level> level;

    DubboCobbleLogger(Logger logger, Supplier<Level> level) {
        this.logger = logger;
        this.level = level;
    }

    @Override
    public void trace(String msg) {
        this.log(Level.TRACE, msg, null);
    }

    @Override
    public void trace(String msg, Object... arguments) {
        this.logArguments(Level.TRACE, msg, arguments);
    }

    @Override
    public void trace(Throwable e) {
        this.trace(e.getMessage(), e);
    }

    @Override
    public void trace(String msg, Throwable e) {
        this.log(Level.TRACE, msg, e);
    }

    @Override
    public void debug(String msg) {
        this.log(Level.DEBUG, msg, null);
    }

    @Override
    public void debug(String msg, Object... arguments) {
        this.logArguments(Level.DEBUG, msg, arguments);
    }

    @Override
    public void debug(Throwable e) {
        this.debug(e.getMessage(), e);
    }

    @Override
    public void debug(String msg, Throwable e) {
        this.log(Level.DEBUG, msg, e);
    }

    @Override
    public void info(String msg) {
        this.log(Level.INFO, msg, null);
    }

    @Override
    public void info(String msg, Object... arguments) {
        this.logArguments(Level.INFO, msg, arguments);
    }

    @Override
    public void info(Throwable e) {
        this.info(e.getMessage(), e);
    }

    @Override
    public void info(String msg, Throwable e) {
        this.log(Level.INFO, msg, e);
    }

    @Override
    public void warn(String msg) {
        this.log(Level.WARN, msg, null);
    }

    @Override
    public void warn(String msg, Object... arguments) {
        this.logArguments(Level.WARN, msg, arguments);
    }

    @Override
    public void warn(Throwable e) {
        this.warn(e.getMessage(), e);
    }

    @Override
    public void warn(String msg, Throwable e) {
        this.log(Level.WARN, msg, e);
    }

    @Override
    public void error(String msg) {
        this.log(Level.ERROR, msg, null);
    }

    @Override
    public void error(String msg, Object... arguments) {
        this.logArguments(Level.ERROR, msg, arguments);
    }

    @Override
    public void error(Throwable e) {
        this.error(e.getMessage(), e);
    }

    @Override
    public void error(String msg, Throwable e) {
        this.log(Level.ERROR, msg, e);
    }

    @Override
    public void warn(String code, String cause, String information, String msg) {
        this.warn(this.diagnosticMessage(code, cause, information, msg));
    }

    @Override
    public void warn(String code, String cause, String information, String msg, Throwable e) {
        this.warn(this.diagnosticMessage(code, cause, information, msg), e);
    }

    @Override
    public void error(String code, String cause, String information, String msg) {
        this.error(this.diagnosticMessage(code, cause, information, msg));
    }

    @Override
    public void error(String code, String cause, String information, String msg, Throwable e) {
        this.error(this.diagnosticMessage(code, cause, information, msg), e);
    }

    @Override
    public boolean isTraceEnabled() {
        return this.enabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return this.enabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return this.enabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return this.enabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return this.enabled(Level.ERROR);
    }

    private boolean enabled(Level level) {
        if (this.level.get().ordinal() > level.ordinal()) {
            return false;
        }
        if (level == Level.TRACE) {
            return this.logger.isTraceEnabled();
        }
        if (level == Level.DEBUG) {
            return this.logger.isDebugEnabled();
        }
        return true;
    }

    private void logArguments(Level level, String message, Object[] arguments) {
        if (this.enabled(level)) {
            FormattingTuple tuple = MessageFormatter.arrayFormat(message, arguments);
            this.write(level, tuple.getMessage(), tuple.getThrowable());
        }
    }

    private void log(Level level, String message, Throwable failure) {
        if (this.enabled(level)) {
            this.write(level, message, failure);
        }
    }

    private void write(Level level, String message, Throwable failure) {
        // Cobble 为 warn/error 提供原始异常参数，保留日志后端的异常处理能力。
        if (level == Level.WARN) {
            this.logger.warn(message, failure);
            return;
        }
        if (level == Level.ERROR) {
            this.logger.error(message, failure);
            return;
        }

        // 其余级别只有文本入口，把异常堆栈保留在同一条日志中。
        String text = message;
        if (failure != null) {
            text = message + System.lineSeparator() + ExceptionUtils.getStackTrace(failure);
        }
        switch (level) {
            case TRACE:
                this.logger.trace(text);
                break;
            case DEBUG:
                this.logger.debug(text);
                break;
            default:
                this.logger.info(text);
                break;
        }
    }

    private String diagnosticMessage(String code, String cause, String information, String message) {
        return message + ", error code: " + code + ", cause: " + cause + ", " + information;
    }
}
