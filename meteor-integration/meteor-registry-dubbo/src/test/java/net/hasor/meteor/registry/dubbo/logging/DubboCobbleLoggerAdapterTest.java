/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.logging;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.apache.dubbo.common.logger.Level;
import org.junit.Test;
import static org.junit.Assert.*;

public class DubboCobbleLoggerAdapterTest {
    @Test
    public void initiallyDefersFilteringToCobble() {
        // 默认不在适配器额外收紧 Cobble 的日志级别。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertEquals(Level.ALL, adapter.getLevel());
    }

    @Test
    public void retainsTheRequestedThreshold() {
        // 适配器自己的阈值可在取得日志对象之后修改。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        adapter.setLevel(Level.WARN);
        assertEquals(Level.WARN, adapter.getLevel());
    }

    @Test
    public void rejectsAnUnspecifiedThreshold() {
        // SPI 的空级别参数不能变成后续日志调用中的空指针。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertThrows(IllegalArgumentException.class, () -> adapter.setLevel(null));
    }

    @Test
    public void createsALoggerByName() {
        // Dubbo 的名称入口取得 Cobble 适配实现。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertTrue(adapter.getLogger("meteor.registry.dubbo") instanceof DubboCobbleLogger);
    }

    @Test
    public void createsALoggerByClass() {
        // 类入口保持相同的日志实现，不要求另行配置实现名称。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertTrue(adapter.getLogger(this.getClass()) instanceof DubboCobbleLogger);
    }

    @Test
    public void existingLoggersSeeThresholdChanges() {
        // 先取得日志，再修改适配器，已有日志对象也遵守新阈值。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        var logger = adapter.getLogger(this.getClass());
        adapter.setLevel(Level.OFF);

        assertFalse(logger.isInfoEnabled());
        assertFalse(logger.isWarnEnabled());
        assertFalse(logger.isErrorEnabled());
    }

    @Test
    public void doesNotOwnALogFile() {
        // 输出目标属于 Cobble 的配置，适配器不维护虚假的文件状态。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertNull(adapter.getFile());
    }

    @Test
    public void acceptsTheAbsenceOfALogFile() {
        // 未指定文件时，不对 Cobble 的输出配置做任何更改。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        adapter.setFile(null);
        assertNull(adapter.getFile());
    }

    @Test
    public void rejectsFileConfigurationThroughDubbo() {
        // 文件配置必须留在 Cobble，不通过 SDK 的共享接口改变应用日志。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        File file = new File("dubbo.log");
        assertThrows(UnsupportedOperationException.class, () -> adapter.setFile(file));
    }

    @Test
    public void reportsAnAvailableAdapter() {
        // Cobble 是模块运行依赖，SPI 可以被显式选择。
        DubboCobbleLoggerAdapter adapter = new DubboCobbleLoggerAdapter();
        assertTrue(adapter.isConfigured());
    }

    @Test
    public void declaresTheOptionalSpiName() throws Exception {
        // 读取插件资源验证注册名称，不改变 Dubbo 的全局 LoggerFactory。
        String path = "META-INF/dubbo/internal/org.apache.dubbo.common.logger.LoggerAdapter";
        ClassLoader loader = this.getClass().getClassLoader();
        String expected = "meteor-cobble=" + DubboCobbleLoggerAdapter.class.getName();
        Enumeration<URL> resources = loader.getResources(path);
        List<String> declarations = new ArrayList<>();
        while (resources.hasMoreElements()) {
            try (InputStream input = resources.nextElement().openStream()) {
                String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                declarations.addAll(descriptor.lines().toList());
            }
        }
        assertTrue(declarations.contains(expected));
    }
}
