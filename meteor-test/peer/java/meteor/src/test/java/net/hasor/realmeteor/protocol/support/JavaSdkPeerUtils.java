/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.support;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 读取 SDK 隔离运行图并管理独立进程；实际 RPC 由对端 SDK 发出。 */
public final class JavaSdkPeerUtils {
    private JavaSdkPeerUtils() {
    }

    public static String classpath(String profile) throws IOException {
        // 每个对端只读取准备阶段导出的所属运行图，不借用 JUnit 或其他 SDK 的依赖。
        JsonNode entries = manifest().path("profiles").path(profile).path("classpath");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IOException("Java SDK profile has no classpath: " + profile);
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw new IOException("Invalid Java SDK classpath entry in profile: " + profile);
            }
            paths.add(entry.textValue());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static JsonNode manifest() throws IOException {
        String configured = System.getProperty("meteor.test.sdk.manifest");
        if (StringUtils.isBlank(configured)) {
            throw new IllegalStateException("Java SDK manifest is not configured: meteor.test.sdk.manifest");
        }
        JsonNode manifest = new ObjectMapper().readTree(Path.of(configured).toFile());
        if (manifest == null || !manifest.isObject()) {
            throw new IOException("Invalid Java SDK manifest: " + configured);
        }
        return manifest;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> exchange(String protocol, String peerClassName, Map<String, Object> request) throws Exception {
        // Gradle 只传入当前 SDK 及其运行依赖，不借用 Meteor 的运行类路径。
        String classpath = classpath(protocol);
        String configured = System.getProperty("java.peer.output.directory", "build/java-peer-logs");
        Path outputDirectory = Files.createDirectories(Path.of(configured).toAbsolutePath());
        Path directory = Files.createTempDirectory(outputDirectory, protocol + "-");
        Path input = directory.resolve("request.bin");
        Path output = directory.resolve("response.bin");
        Path log = directory.resolve("peer.log");
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(input))) {
            stream.writeObject(request);
        }

        // 参数顺序对应对端 main 的输入、输出文件；使用独立日志避免子进程输出堵塞。
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(peerClassName);
        command.add(input.toString());
        command.add(output.toString());
        ProcessBuilder builder = new ProcessBuilder(command) //
                .redirectErrorStream(true) //
                .redirectOutput(log.toFile());
        Process process = ContainerProcess.start("java", builder);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Java SDK peer timed out; log: " + log);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                String details = Files.readString(log, StandardCharsets.UTF_8);
                throw new AssertionError("Java SDK peer failed; log: " + log + "\n" + details);
            }

            // 本地结果仅含 JDK 值；不能将 SDK 异常或 Meteor 对象跨进程传递。
            try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(output))) {
                return (Map<String, Object>) stream.readObject();
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Java SDK peer did not terminate; log: " + log);
                }
            }
        }
    }
}
