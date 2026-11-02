/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.logging;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.common.logger.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DubboCobbleLoggerTest {
    private final RecordingCobbleLogger  output = new RecordingCobbleLogger();
    private final AtomicReference<Level> level  = new AtomicReference<>(Level.ALL);
    private final DubboCobbleLogger      logger = new DubboCobbleLogger(this.output, this.level::get);

    @Test
    public void forwardsTraceText() {
        // 文本日志保持原级别与正文。
        this.logger.trace("trace message");
        assertEquals("trace", this.output.getLevel());
        assertEquals("trace message", this.output.getMessage());
    }

    @Test
    public void forwardsDebugText() {
        // 调试正文不在适配层重新解释。
        this.logger.debug("debug message");
        assertEquals("debug", this.output.getLevel());
        assertEquals("debug message", this.output.getMessage());
    }

    @Test
    public void forwardsInfoText() {
        // 信息日志交给 Cobble 的信息入口。
        this.logger.info("info message");
        assertEquals("info", this.output.getLevel());
        assertEquals("info message", this.output.getMessage());
    }

    @Test
    public void forwardsWarningText() {
        // 警告正文不被当作错误输出。
        this.logger.warn("warn message");
        assertEquals("warn", this.output.getLevel());
        assertEquals("warn message", this.output.getMessage());
    }

    @Test
    public void forwardsErrorText() {
        // 错误日志交给 Cobble 的错误入口。
        this.logger.error("error message");
        assertEquals("error", this.output.getLevel());
        assertEquals("error message", this.output.getMessage());
    }

    @Test
    public void formatsTraceArguments() {
        // SDK 占位参数在进入 Cobble 的文本入口之前展开。
        this.logger.trace("service {} instance {}", "echo", 7);
        assertEquals("trace", this.output.getLevel());
        assertEquals("service echo instance 7", this.output.getMessage());
    }

    @Test
    public void formatsDebugArguments() {
        // 调试日志采用与 SDK 相同的占位语义。
        this.logger.debug("service {}", "echo");
        assertEquals("debug", this.output.getLevel());
        assertEquals("service echo", this.output.getMessage());
    }

    @Test
    public void formatsInfoArguments() {
        // 信息日志的数值参数按普通占位参数处理。
        this.logger.info("instance {}", 7);
        assertEquals("info", this.output.getLevel());
        assertEquals("instance 7", this.output.getMessage());
    }

    @Test
    public void formatsWarningArguments() {
        // 警告日志仍保留字符串参数中的内容。
        this.logger.warn("service {}", "echo");
        assertEquals("warn", this.output.getLevel());
        assertEquals("service echo", this.output.getMessage());
    }

    @Test
    public void formatsErrorArguments() {
        // 错误日志的参数替换不需要 SLF4J。
        this.logger.error("service {}", "echo");
        assertEquals("error", this.output.getLevel());
        assertEquals("service echo", this.output.getMessage());
    }

    @Test
    public void preservesEscapedPlaceholders() {
        // 使用 SDK 自带格式化器保留转义占位符。
        this.logger.info("literal \\{} actual {}", "echo");
        assertEquals("literal {} actual echo", this.output.getMessage());
    }

    @Test
    public void formatsArrayArguments() {
        // 数组是一个参数，正文展示其元素而非 JVM 对象标识。
        this.logger.info("instances {}", new Object[] { new int[] { 3, 7 } });
        assertEquals("instances [3, 7]", this.output.getMessage());
    }

    @Test
    public void retainsTraceExceptionStack() {
        // Cobble 的 trace 只有文本方法，异常堆栈并入本条正文。
        IllegalStateException failure = new IllegalStateException("trace failed");
        this.logger.trace(failure);
        assertEquals("trace", this.output.getLevel());
        assertTrue(this.output.getMessage().contains("IllegalStateException: trace failed"));
        assertTrue(this.output.getMessage().contains("retainsTraceExceptionStack"));
    }

    @Test
    public void retainsDebugExceptionStack() {
        // 调试异常不提升到警告或错误级别。
        IllegalStateException failure = new IllegalStateException("debug failed");
        this.logger.debug(failure);
        assertEquals("debug", this.output.getLevel());
        assertTrue(this.output.getMessage().contains("IllegalStateException: debug failed"));
    }

    @Test
    public void retainsInfoExceptionStack() {
        // 信息级别的异常同样保留堆栈。
        IllegalStateException failure = new IllegalStateException("info failed");
        this.logger.info(failure);
        assertEquals("info", this.output.getLevel());
        assertTrue(this.output.getMessage().contains("IllegalStateException: info failed"));
    }

    @Test
    public void forwardsWarningException() {
        // Cobble 的警告入口能接收原始异常，不把它改成新异常。
        IllegalStateException failure = new IllegalStateException("warn failed");
        this.logger.warn(failure);
        assertEquals("warn failed", this.output.getMessage());
        assertSame(failure, this.output.getFailure());
    }

    @Test
    public void forwardsErrorException() {
        // 原始错误对象完整交给日志后端。
        IllegalStateException failure = new IllegalStateException("error failed");
        this.logger.error(failure);
        assertEquals("error failed", this.output.getMessage());
        assertSame(failure, this.output.getFailure());
    }

    @Test
    public void retainsTheMessageOfAnExplicitInfoException() {
        // 用户提供的正文保留在异常堆栈之前。
        IllegalStateException failure = new IllegalStateException("operation failed");
        this.logger.info("registry update", failure);
        assertTrue(this.output.getMessage().startsWith("registry update" + System.lineSeparator()));
        assertTrue(this.output.getMessage().contains("operation failed"));
    }

    @Test
    public void extractsTheTrailingExceptionFromArguments() {
        // 可变参数末项的异常不消费正文占位符，仍传给 Cobble。
        IllegalStateException failure = new IllegalStateException("update failed");
        this.logger.warn("service {}", "echo", failure);
        assertEquals("service echo", this.output.getMessage());
        assertSame(failure, this.output.getFailure());
    }

    @Test
    public void keepsWarningDiagnosticContext() {
        // 带错误码的 SDK 警告保留所有诊断字段。
        this.logger.warn("1-2", "connection lost", "retry registry", "update failed");
        assertEquals("warn", this.output.getLevel());
        String expected = "update failed, error code: 1-2, cause: connection lost, retry registry";
        assertEquals(expected, this.output.getMessage());
    }

    @Test
    public void keepsWarningDiagnosticException() {
        // 扩展诊断字段与原始异常可同时送达。
        IllegalStateException failure = new IllegalStateException("warn failed");
        this.logger.warn("1-2", "connection lost", "retry registry", "update failed", failure);
        assertTrue(this.output.getMessage().contains("error code: 1-2"));
        assertSame(failure, this.output.getFailure());
    }

    @Test
    public void keepsErrorDiagnosticContext() {
        // 带错误码的错误日志保持错误级别。
        this.logger.error("1-2", "connection lost", "retry registry", "update failed");
        assertEquals("error", this.output.getLevel());
        String expected = "update failed, error code: 1-2, cause: connection lost, retry registry";
        assertEquals(expected, this.output.getMessage());
    }

    @Test
    public void keepsErrorDiagnosticException() {
        // 错误诊断不通过包装改变异常身份。
        IllegalStateException failure = new IllegalStateException("error failed");
        this.logger.error("1-2", "connection lost", "retry registry", "update failed", failure);
        assertTrue(this.output.getMessage().contains("error code: 1-2"));
        assertSame(failure, this.output.getFailure());
    }

    @Test
    public void warningThresholdRejectsInformation() {
        // 阈值同时影响能力查询与实际写入。
        this.level.set(Level.WARN);
        this.logger.info("not emitted");
        assertFalse(this.logger.isInfoEnabled());
        assertNull(this.output.getLevel());
    }

    @Test
    public void warningThresholdAllowsWarnings() {
        // 达到阈值的警告正常送达。
        this.level.set(Level.WARN);
        this.logger.warn("emitted");
        assertTrue(this.logger.isWarnEnabled());
        assertEquals("warn", this.output.getLevel());
    }

    @Test
    public void errorThresholdAllowsErrors() {
        // ERROR 阈值仍保留错误日志。
        this.level.set(Level.ERROR);
        this.logger.error("emitted");
        assertTrue(this.logger.isErrorEnabled());
        assertEquals("error", this.output.getLevel());
    }

    @Test
    public void offThresholdSuppressesErrors() {
        // OFF 不是比 ERROR 更高的输出级别，而是完全关闭适配器。
        this.level.set(Level.OFF);
        this.logger.error("not emitted");
        assertFalse(this.logger.isErrorEnabled());
        assertNull(this.output.getLevel());
    }

    @Test
    public void cobbleControlsTraceAvailability() {
        // 即使适配器放行，Cobble 关闭 trace 时也不输出。
        this.output.setTraceEnabled(false);
        this.logger.trace("not emitted");
        assertFalse(this.logger.isTraceEnabled());
        assertNull(this.output.getLevel());
    }

    @Test
    public void cobbleControlsDebugAvailability() {
        // Cobble 的 debug 开关是最终日志能力的一部分。
        this.output.setDebugEnabled(false);
        this.logger.debug("not emitted");
        assertFalse(this.logger.isDebugEnabled());
        assertNull(this.output.getLevel());
    }

    @Test
    public void disabledArgumentsAreNotFormatted() {
        // 过滤发生在展开参数之前，关闭日志不会执行参数的 toString。
        this.level.set(Level.OFF);
        Object argument = new Object() {
            @Override
            public String toString() {
                fail("Disabled log arguments must not be formatted");
                return "unreachable";
            }
        };
        this.logger.info("service {}", argument);
        assertNull(this.output.getLevel());
    }
}
