/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.support;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 SDK 运行清单的隔离选择及缺失配置，不启动 SDK 或外部服务。 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class JavaSdkPeerUtilsTest {
    private final String previousManifest = System.getProperty("meteor.test.sdk.manifest");
    @TempDir
    private Path directory;

    @BeforeEach
    public void configureManifest() throws Exception {
        Path manifest = Path.of(getClass().getResource("/sdk/isolated-runtimes.json").toURI());
        System.setProperty("meteor.test.sdk.manifest", manifest.toString());
    }

    @AfterEach
    public void restoreManifest() {
        if (this.previousManifest == null) {
            System.clearProperty("meteor.test.sdk.manifest");
        } else {
            System.setProperty("meteor.test.sdk.manifest", this.previousManifest);
        }
    }

    @Test
    public void selectsOnlyRequestedSdkProfile() throws Exception {
        // 历史 SDK 持有自己的输出和依赖，路径中的空格与反斜线不能被再次解释。
        String classpath = JavaSdkPeerUtils.classpath("dubboLegacy");

        // 原有顺序保持不变，当前版 SDK 的输出和依赖不会加入历史版本。
        String expected = String.join(File.pathSeparator, List.of( //
                "/sdk/legacy/classes", "C:\\SDK cache\\dubbo-2.7.23.jar"));
        assertEquals(expected, classpath);
        assertFalse(classpath.contains("/sdk/current/classes"));
        assertFalse(classpath.contains("dubbo-3.3.6.jar"));
    }

    @Test
    public void rejectsUnknownProfileInsteadOfUsingJUnitClasspath() {
        // 清单没有所需版本时明确失败，不能回退到包含 Meteor 的 JUnit 类路径。
        IOException failure = assertThrows(IOException.class, () -> JavaSdkPeerUtils.classpath("unknown"));
        assertTrue(failure.getMessage().contains("unknown"));
    }

    @Test
    public void rejectsEmptyProfileInsteadOfUsingJUnitClasspath() {
        // 空运行图不能被当作当前进程默认类路径启动。
        IOException failure = assertThrows(IOException.class, () -> JavaSdkPeerUtils.classpath("empty"));
        assertTrue(failure.getMessage().contains("empty"));
    }

    @Test
    public void requiresConfiguredManifest() {
        // 未交付运行清单时，不能猜测某个旧构建的 SDK 路径。
        System.clearProperty("meteor.test.sdk.manifest");

        IllegalStateException failure = assertThrows(IllegalStateException.class, //
                () -> JavaSdkPeerUtils.classpath("dubbo"));
        assertTrue(failure.getMessage().contains("meteor.test.sdk.manifest"));
    }

    @Test
    public void rejectsMissingManifestFile() {
        // 配置指向不存在的清单时保留文件读取错误，不能隐式使用别的版本。
        Path missing = this.directory.resolve("missing-runtimes.json");
        System.setProperty("meteor.test.sdk.manifest", missing.toString());

        assertThrows(IOException.class, () -> JavaSdkPeerUtils.classpath("dubbo"));
    }
}
