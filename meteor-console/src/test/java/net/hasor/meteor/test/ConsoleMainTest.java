/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.boot.web.WebServerConfig;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.global.http.ConsoleJsonRender;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.web.binder.RenderDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class ConsoleMainTest {
    @TempDir
    public           Path      temporary;
    private          String    previousConfiguration;
    private          String    previousHome;
    private          Thread    application;
    private volatile Throwable applicationFailure;

    @BeforeEach
    public void isolateConfigurationProperties() {
        this.previousConfiguration = System.getProperty("config");
        this.previousHome = System.getProperty("user.home");
        System.clearProperty("config");
        System.setProperty("user.home", this.temporary.toString());
    }

    @AfterEach
    public void restoreConfigurationProperties() throws InterruptedException {
        try {
            if (this.application != null) {
                // 中断主入口的 join，由其 try-with-resources 完整关闭应用。
                this.application.interrupt();
                this.application.join(15000);
                assertFalse(this.application.isAlive(), "ConsoleMain 未完成关闭");
                Throwable failure = this.applicationFailure;
                assertNotNull(failure, "主入口应在中断后退出 join");
                assertEquals(0, failure.getSuppressed().length, "关闭应用时发生异常");
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertInstanceOf(InterruptedException.class, failure);
            }
        } finally {
            if (this.previousConfiguration == null) {
                System.clearProperty("config");
            } else {
                System.setProperty("config", this.previousConfiguration);
            }
            if (this.previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", this.previousHome);
            }
        }
    }

    @Test
    public void defaultConfigurationLoadsFromUserHome() throws Exception {
        // 未指定 config 时，主入口读取用户目录中的配置。
        Path directory = Files.createDirectories(this.temporary.resolve(".meteor"));
        int port = writeConfiguration(directory.resolve("meteor.properties"), "home");

        JsonNode session = startApplication(port);
        assertEquals("home", session.path("localUnit").asText());
    }

    @Test
    public void blankConfigurationUsesUserHome() throws Exception {
        // 空白参数与未指定参数一致，仍加载用户目录中的配置。
        System.setProperty("config", "  ");
        Path directory = Files.createDirectories(this.temporary.resolve(".meteor"));
        int port = writeConfiguration(directory.resolve("meteor.properties"), "home");

        JsonNode session = startApplication(port);
        assertEquals("home", session.path("localUnit").asText());
    }

    @Test
    public void absoluteConfigurationUsesRequestedFile() throws Exception {
        // 外部配置可以位于任意绝对路径，包括带空格的目录。
        Path directory = Files.createDirectories(this.temporary.resolve("external configuration"));
        Path configuration = directory.resolve("meteor.properties");
        int port = writeConfiguration(configuration, "east");
        System.setProperty("config", configuration.toString());

        JsonNode session = startApplication(port);
        assertEquals("east", session.path("localUnit").asText());
    }

    @Test
    public void sourceLaunchResolvesRelativeConfigurationFromWorkingDirectory() throws Exception {
        // 源码运行没有 Boot JAR，显式相对路径按工作目录定位。
        Path configuration = this.temporary.resolve("meteor.properties");
        int port = writeConfiguration(configuration, "east");
        Path workingDirectory = Path.of("").toAbsolutePath();
        System.setProperty("config", workingDirectory.relativize(configuration).toString());

        JsonNode session = startApplication(port);
        assertEquals("east", session.path("localUnit").asText());
    }

    @Test
    public void missingExplicitConfigurationFailsBeforeStartup() {
        // 显式配置不存在时，由入口报错，不回退到内置资源。
        Path missing = this.temporary.resolve("missing.properties");
        System.setProperty("config", missing.toString());

        NoSuchFileException failure = assertThrows(NoSuchFileException.class, () -> ConsoleMain.main(new String[0]));
        assertEquals(missing.toString(), failure.getFile());
    }

    @Test
    public void explicitConfigurationRejectsDirectory() {
        // 目录不是配置文件，不能静默按缺省配置启动。
        System.setProperty("config", this.temporary.toString());

        NoSuchFileException failure = assertThrows(NoSuchFileException.class, () -> ConsoleMain.main(new String[0]));
        assertEquals(this.temporary.toString(), failure.getFile());
    }

    @Test
    public void missingUserConfigurationStartsWithPackagedDefaults() throws Exception {
        // 不提供用户配置，仅关闭 HTTP 监听；真实 Boot 装配仍应加载内置默认值。
        Path configuration = this.temporary.resolve(".meteor/meteor.properties");
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration.toUri().toString())//
                .property("hasor.boot.web.connectors.http.enabled", false);
        try (BootApplication application = boot.start()) {
            Settings settings = application.getAppContext().getSettings();
            assertEquals("net.hasor.meteor.console", settings.getString("hasor.loadPackages"));
            assertEquals("META-INF/resources", WebServerConfig.of(settings, ConsoleMain.class).getStaticLocation());
            assertFalse(settings.getBoolean("hasor.boot.web.health.enabled"));

            // 业务默认值通过唯一配置 Bean 注入，未提供存储地址时不启动管理 RPC。
            MeteorConfig config = application.getAppContext().getInstance(MeteorConfig.class);
            assertEquals(this.temporary.resolve(".meteor/data").toString(), config.getDataDirectory());
            assertEquals("default", config.getUnit());
            assertEquals(300000, config.getCommandContextTtl());
            assertFalse(config.isSecurityEnabled());
            assertFalse(config.isSecureCookie());
            assertFalse(config.isRegistryEnabled());

            // 显式 JSON 渲染器接管默认渲染，正常响应也复用控制台 JSON 工具。
            RenderDef json = application.getAppContext().findBindingBean("json", RenderDef.class);
            assertNotNull(json);
            var renderer = assertDoesNotThrow(() -> json.newEngine(application.getAppContext()));
            assertInstanceOf(ConsoleJsonRender.class, renderer);

            // 默认 data.directory 经注入生效，配置文件本身仍保持缺失。
            assertNotNull(application.getAppContext().getInstance(ClusterDao.class));
            assertTrue(Files.isDirectory(this.temporary.resolve(".meteor/data")));
            assertFalse(Files.exists(configuration));
        }
    }

    @Test
    public void userConfigurationOverridesPackagedDefaultsForManagedBeans() throws Exception {
        // 用户文件只写运行参数，扫描范围由 Boot 根据 ConsoleMain 所在包推导。
        Path directory = this.temporary.resolve("connections");
        Path configuration = this.temporary.resolve("meteor.properties");
        Properties properties = new Properties();
        properties.setProperty("hasor.boot.web.connectors.http.enabled", "false");
        properties.setProperty("data.directory", directory.toString());
        properties.setProperty("meteor.unit", "east");
        properties.setProperty("security.enable", "true");
        properties.setProperty("security.account", "operator:test-password");
        try (Writer writer = Files.newBufferedWriter(configuration)) {
            properties.store(writer, null);
        }
        System.setProperty("config", configuration.toString());

        // 使用与主入口相同的 Boot 配置入口，不单独解析或合并 Properties。
        Boot boot = new Boot()//
                .sources(ConsoleMain.class)//
                .hconfigFile(configuration.toUri().toString());
        try (BootApplication application = boot.start()) {
            Settings settings = application.getAppContext().getSettings();
            assertEquals("META-INF/resources", WebServerConfig.of(settings, ConsoleMain.class).getStaticLocation());
            MeteorConfig config = application.getAppContext().getInstance(MeteorConfig.class);
            assertEquals(directory.toString(), config.getDataDirectory());
            assertEquals("east", config.getUnit());
            assertEquals("operator:test-password", config.getSecurityAccount());
            assertTrue(config.isSecurityEnabled());
            assertTrue(application.getAppContext().getInstance(SessionService.class).isSecurityEnabled());

            // DAO 使用覆盖后的数据目录，Meteor Bean 也按完整运行依赖完成启动。
            assertNotNull(application.getAppContext().getInstance(ClusterDao.class));
            assertNotNull(application.getAppContext().getInstance(MetContext.class));
            assertTrue(Files.isDirectory(directory));
            assertFalse(Files.exists(this.temporary.resolve(".meteor/data")));
        }
    }

    private int writeConfiguration(Path configuration, String unit) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Properties properties = new Properties();
        properties.setProperty("hasor.boot.web.connectors.http.port", Integer.toString(port));
        properties.setProperty("meteor.unit", unit);
        try (Writer writer = Files.newBufferedWriter(configuration)) {
            properties.store(writer, null);
        }
        return port;
    }

    private JsonNode startApplication(int port) throws Exception {
        this.application = new Thread(() -> {
            try {
                ConsoleMain.main(new String[0]);
            } catch (Throwable failure) {
                this.applicationFailure = failure;
            }
        }, "console-main-test");
        this.application.start();

        // 以真实 HTTP 响应等待应用就绪，不读取主入口内部状态。
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        URI address = URI.create("http://127.0.0.1:" + port + "/api/auth/session");
        HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(1)).GET().build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            assertTrue(this.application.isAlive(), () -> "ConsoleMain 启动失败: " + this.applicationFailure);
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException failure) {
                lastFailure = failure;
                TimeUnit.MILLISECONDS.sleep(50);
                continue;
            }
            assertEquals(200, response.statusCode(), response.body());
            String contentType = response.headers().firstValue("Content-Type").orElseThrow();
            assertTrue(contentType.startsWith("application/json"), contentType);
            return JsonUtils.readTree(response.body());
        }
        throw new AssertionError("ConsoleMain 未在期限内提供 HTTP 服务", lastFailure);
    }
}
