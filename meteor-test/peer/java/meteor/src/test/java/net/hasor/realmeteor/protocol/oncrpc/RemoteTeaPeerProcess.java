/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.protocol.support.JavaSdkPeerUtils;

/** 拥有独立 RemoteTea JVM 和日志；调用、关闭都保持有界等待。 */
final class RemoteTeaPeerProcess implements AutoCloseable {
    private final Process process;
    private final Path    log;

    private RemoteTeaPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static RemoteTeaPeerProcess start(String transport, int port, String value, String scenario) throws Exception {
        // Gradle 只交付标准测试输出与 RemoteTea SDK，不能继承 Meteor 的运行类路径。
        String classpath = JavaSdkPeerUtils.classpath("oncrpc");
        String configuredOutput = System.getProperty("oncrpc.peer.output.directory", "build/reports/oncrpc-peer");
        Path directory = Path.of(configuredOutput).toAbsolutePath();
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, "remotetea-" + transport + "-", ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String main = "net.hasor.peer.protocol.oncrpc.RemoteTeaClientPeer";
        ProcessBuilder command = new ProcessBuilder(java.toString(), "-cp", classpath, main, //
                transport, ContainerProcess.runnerHost(), Integer.toString(port), value, scenario);
        ProcessBuilder builder = command.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = ContainerProcess.start("java", builder);
        return new RemoteTeaPeerProcess(process, log);
    }

    void requireResult(String expected) throws Exception {
        String output = this.completedOutput();
        String marker = Base64.getEncoder().encodeToString(expected.getBytes(StandardCharsets.UTF_8));
        boolean matched = output.lines().anyMatch(line -> StringUtils.equals("RESULT " + marker, line));
        if (this.process.exitValue() != 0 || !matched) {
            throw new AssertionError("RemoteTea Java client failed; log: " + this.log + "\n" + output);
        }
    }

    void requireArgumentRejection() throws Exception {
        // 只有 SDK 识别到远端 GARBAGE_ARGS 才输出此标记，不能接受超时或未知异常。
        String output = this.completedOutput();
        boolean matched = output.lines().anyMatch(line -> StringUtils.equals("REJECTED RPC_CANTDECODEARGS", line));
        if (this.process.exitValue() != 0 || !matched) {
            String message = "RemoteTea Java client did not receive GARBAGE_ARGS; log: " + this.log;
            throw new AssertionError(message + "\n" + output);
        }
    }

    private String completedOutput() throws Exception {
        // 输出直接写入日志，避免管道满导致进程阻塞；超时明确失败且保留完整输出。
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw new AssertionError("RemoteTea Java client timed out; log: " + this.log);
        }
        return Files.readString(this.log, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        // 子进程只执行一次 RPC；正常退出无需干预，失败路径回收本对象创建的进程。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }
}
