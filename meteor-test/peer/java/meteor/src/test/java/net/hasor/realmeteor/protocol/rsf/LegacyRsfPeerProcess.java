/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.rsf;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 仅拥有独立旧版 RSF JVM 与控制日志，不参与网络协议编码。 */
final class LegacyRsfPeerProcess implements AutoCloseable {
    private final Process process;
    private final Path log;

    private LegacyRsfPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static LegacyRsfPeerProcess start(String... arguments) throws Exception {
        // SDK 运行图由标准测试构建独立解析，进程只借用共享 Java 容器。
        String classpath = System.getProperty("rsf.java.peer.classpath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("Legacy RSF Java peer classpath is not configured");
        }
        String configured = System.getProperty("java.peer.output.directory", "build/java-peer-logs");
        Path directory = Files.createDirectories(Path.of(configured).toAbsolutePath());
        Path log = Files.createTempFile(directory, "legacy-rsf-", ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add("net.hasor.peer.protocol.rsf.LegacyRsfPeer");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = ContainerProcess.start("java", builder);
        process.getOutputStream().close();
        return new LegacyRsfPeerProcess(process, log);
    }

    String awaitAddress() throws Exception {
        // 就绪标记来自 SDK 完成监听与发布之后，不以固定延迟假定启动完成。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String output = this.output();
            for (String line : output.split("\\R")) {
                if (line.startsWith("READY ")) {
                    return line.substring("READY ".length()).trim();
                }
            }
            if (!this.process.isAlive()) {
                throw new AssertionError("Legacy RSF provider exited; log: " + this.log + "\n" + output);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Legacy RSF provider timed out; log: " + this.log + "\n" + this.output());
    }

    String awaitResult() throws Exception {
        // 标记只传递普通 UTF-8 结果；SDK 异常与控制台输出保留在独立日志。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new AssertionError("Legacy RSF client timed out; log: " + this.log + "\n" + this.output());
        }
        String output = this.output();
        if (this.process.exitValue() != 0) {
            throw new AssertionError("Legacy RSF client failed; log: " + this.log + "\n" + output);
        }
        for (String line : output.split("\\R")) {
            if (line.startsWith("RESULT ")) {
                byte[] bytes = Base64.getDecoder().decode(line.substring("RESULT ".length()));
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Legacy RSF result missing; log: " + this.log + "\n" + output);
    }

    private String output() throws Exception {
        return Files.readString(this.log, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        // 只回收当前场景拥有的 SDK 进程，语言容器由外层编排器管理。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Legacy RSF peer did not terminate; log: " + this.log);
                }
            }
        }
    }
}
