/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.logging;
import java.io.File;
import net.hasor.cobble.logging.LoggerFactory;
import org.apache.dubbo.common.logger.Level;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerAdapter;

/** Dubbo 的可选 Cobble 日志 SPI；由使用方通过 dubbo.application.logger=meteor-cobble 选择。 */
public final class DubboCobbleLoggerAdapter implements LoggerAdapter {
    private volatile Level level = Level.ALL;

    @Override
    public Logger getLogger(Class<?> key) {
        return this.getLogger(key.getName());
    }

    @Override
    public Logger getLogger(String key) {
        return new DubboCobbleLogger(LoggerFactory.getLogger(key), this::getLevel);
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    /** 仅调整此适配器的过滤阈值，Cobble 的输出配置仍由应用负责。 */
    @Override
    public void setLevel(Level level) {
        if (level == null) {
            throw new IllegalArgumentException("Dubbo log level is required");
        }
        this.level = level;
    }

    @Override
    public File getFile() {
        return null;
    }

    /** 日志输出目标属于 Cobble，不通过 Dubbo 改写共享日志配置。 */
    @Override
    public void setFile(File file) {
        if (file != null) {
            throw new UnsupportedOperationException("Configure log files through Cobble logging");
        }
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
