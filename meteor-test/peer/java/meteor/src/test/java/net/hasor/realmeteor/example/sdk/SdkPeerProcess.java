/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.sdk;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.Assert.*;

/** 管理测试自有 Java 对端的独占进程、有界等待和保留日志。 */
final class SdkPeerProcess implements AutoCloseable {
    private final Process process;
    private final Path log;

    private SdkPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static SdkPeerProcess meteorMain(String protocol, String role, String target, String... registryParameters) throws Exception {
        String classpath = requiredProperty("meteor.test.application.classpath");
        String main = "net.hasor.peer.application.OrderApplicationMain";
        List<String> arguments = new ArrayList<>();
        arguments.add(protocol);
        arguments.add(role);
        arguments.addAll(List.of(registryParameters));
        String runtime = "java-meteor";
        if (role.endsWith("Provider")) {
            runtime = "java-meteor-peer";
        }
        return launch(runtime, protocol, main, classpath, target, arguments);
    }

    static SdkPeerProcess sdkMain(String protocol, String role, String target, String... registryParameters) throws Exception {
        // 对端只获得 SDK 依赖；测试输出目录不赋予 SDK 加载 Meteor 内核的能力。
        String suffix = ".classpath";
        if (StringUtils.equals("Provider", role)) {
            suffix = ".providerClasspath";
        } else if (StringUtils.equals("RegistryProvider", role) || StringUtils.equals("RegistryConsumer", role)) {
            suffix = ".registryClasspath";
        }
        String property = "meteor.test.sdk." + protocol;
        String classpath = System.getProperty(property + suffix);
        if (StringUtils.isBlank(classpath)) {
            classpath = requiredProperty(property + ".classpath");
        }
        return sdkWithClasspath(protocol, role, target, classpath, protocol, registryParameters);
    }

    static SdkPeerProcess sdkVersionMain(String protocol, String profile, String role, String target) throws Exception {
        // 显式选择当前用例的隔离运行图，不修改其它并行场景的系统属性。
        String classpath = requiredProperty("meteor.test.sdk.profile." + profile + ".classpath");
        return sdkWithClasspath(protocol, role, target, classpath, protocol + "-" + profile);
    }

    static List<Object[]> compatibilityCases(String role) throws Exception {
        // 版本来自构建实际解析的 SDK 图，JUnit 参数名与运行时选择使用同一份清单。
        String configuration = requiredProperty("meteor.test.sdk.compatibility");
        JsonNode entries = new ObjectMapper().readTree(configuration);
        List<Object[]> cases = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (StringUtils.equals(role, entry.required("role").textValue())) {
                String protocol = entry.required("protocol").textValue();
                String profile = entry.required("profile").textValue();
                String sdk = entry.required("sdk").textValue();
                String version = entry.required("version").textValue();
                cases.add(new Object[] { protocol, profile, sdk + "@" + version });
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("No Java SDK compatibility cases for role: " + role);
        }
        return cases;
    }

    private static SdkPeerProcess sdkWithClasspath(String protocol, String role, String target, String classpath,
            String logPrefix, String... registryParameters) throws Exception {
        // 校验实际使用的图，不能因版本切换而将 Meteor 内核带入官方对端。
        List<URL> entries = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            entries.add(Path.of(entry).toUri().toURL());
        }
        URL[] urls = entries.toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            URL meteorContext = loader.getResource("net/hasor/meteor/MetContext.class");
            assertNull("SDK runtime contains Meteor: " + protocol, meteorContext);
            String bootstrap = "net/hasor/meteor/bootstrap/MetConfiguration.class";
            assertNull("SDK runtime contains Meteor bootstrap: " + protocol, loader.getResource(bootstrap));
        }
        String main = "net.hasor.peer.sdk." + protocol + ".Sdk" + role + "Main";
        return launch("java", logPrefix, main, classpath, target, List.of(registryParameters));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("Required Java peer property is missing: " + name);
        }
        return value;
    }

    private static SdkPeerProcess launch(String runtime, String protocol, String main, String classpath, String target,
            List<String> arguments)
            throws Exception {
        // 日志按进程独立保存，失败时可直接回看对应的测试入口输出。
        String configured = System.getProperty("meteor.test.sdk.logDirectory", "build/sdk-peer-logs");
        Path directory = Files.createDirectories(Path.of(configured).toAbsolutePath());
        String role = main.substring(main.lastIndexOf('.') + 1);
        Path log = Files.createTempFile(directory, protocol + "-" + role + "-", ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        if (StringUtils.equals("java", runtime)) {
            // 旧版 SDK 的代理与类型读写器在自己的 JVM 内反射访问 JDK 类。
            command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(classpath);
        command.add(main);
        command.addAll(arguments);

        // 每一端独立运行测试 Main，注册发现场景只额外传入注册中心位置和数据范围。
        ProcessBuilder launcher = new ProcessBuilder(command) //
                .redirectErrorStream(true) //
                .redirectOutput(log.toFile());
        launcher.environment().put("METEOR_ORDER_BIND_HOST", "0.0.0.0");
        launcher.environment().put("METEOR_ORDER_PORT", "0");
        if (target != null) {
            launcher.environment().put("METEOR_ORDER_TARGET", target);
        }
        Process process = ContainerProcess.start(runtime, launcher);
        process.getOutputStream().close();
        return new SdkPeerProcess(process, log);
    }

    String awaitReady(String prefix) throws Exception {
        // 提供者完成发布后报告真实监听端口，后续消费者通过容器 DNS 访问。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String output = this.output();
            for (String line : output.split("\\R")) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
            assertTrue("Provider exited before ready; log: " + this.log + "\n" + output, this.process.isAlive());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider startup timed out; log: " + this.log + "\n" + this.output());
    }

    JsonNode awaitOrder() throws Exception {
        // 消费者完成保存、查询并退出，最后一条 JSON 就是查询到的业务结果。
        boolean finished = this.process.waitFor(60, TimeUnit.SECONDS);
        String output = this.output();
        assertTrue("Consumer timed out; log: " + this.log + "\n" + output, finished);
        assertEquals("Consumer failed; log: " + this.log + "\n" + output, 0, this.process.exitValue());
        List<String> results = output.lines().filter(line -> line.startsWith("{")).toList();
        assertFalse("Missing order JSON; log: " + this.log + "\n" + output, results.isEmpty());
        return new ObjectMapper().readTree(results.get(results.size() - 1));
    }

    private String output() throws Exception {
        return Files.readString(this.log, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        // 只回收当前辅助对象创建的进程，日志保留供复现和审阅。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                assertTrue("Java peer did not terminate; log: " + this.log, this.process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
