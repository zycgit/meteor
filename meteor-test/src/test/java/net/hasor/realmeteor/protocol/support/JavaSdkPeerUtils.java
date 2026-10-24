/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.support;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 仅管理独立 Java SDK 进程和本地控制文件；实际 RPC 由对端 SDK 发出。 */
public final class JavaSdkPeerUtils {
    private JavaSdkPeerUtils() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> exchange(String protocol, Class<?> peerType, Map<String, Object> request) throws Exception {
        // Gradle 只传入当前 SDK 及其运行依赖，不借用 Meteor 的运行类路径。
        String classpath = System.getProperty(protocol + ".java.peer.classpath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("Java SDK peer classpath is not configured: " + protocol);
        }
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
        command.add(peerType.getName());
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
