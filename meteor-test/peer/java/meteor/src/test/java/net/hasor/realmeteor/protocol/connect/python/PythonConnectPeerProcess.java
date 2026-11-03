/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.connect.python;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 仅持有当前官方 Python SDK 进程及日志，借用外层创建的语言容器。 */
final class PythonConnectPeerProcess implements AutoCloseable {
    private final Process process;
    private final Path log;

    private PythonConnectPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static PythonConnectPeerProcess provider(String version, String protocol, String messageApi) throws IOException {
        return start("provider", version, protocol, messageApi, null);
    }

    static PythonConnectPeerProcess consumer(String version, String protocol, String messageApi,
            String target) throws IOException {
        return start("consumer", version, protocol, messageApi, target);
    }

    private static PythonConnectPeerProcess start(String role, String version, String protocol, String messageApi,
            String target) throws IOException {
        // SDK 和生成器均使用容器中独立安装的锁定版本，不借用 Meteor 的编解码器。
        Path source = Path.of(System.getProperty("meteor.test.peerDirectory", "peer"));
        Path script = source.resolve("python/connect/peer.py");
        List<String> command = new ArrayList<>();
        String executable = "/opt/meteor-peer/python-connect-" + version + "/bin/python";
        String generated = "/opt/meteor-peer/python-connect-" + version + "-" + messageApi + "-generated";
        command.addAll(List.of(executable, script.toString(), role));
        command.addAll(List.of("--protocol", protocol, "--generated", generated));
        command.addAll(List.of("--sdk-version", version, "--message-api", messageApi));
        if (target != null) {
            command.addAll(List.of("--target", target));
        }

        // 完整输出交给现有容器进程框架留档；场景只从日志读取 READY 和 RESULT 控制消息。
        String logs = System.getProperty("python.grpc.logs", "build/python-grpc-peer-logs");
        Path directory = Path.of(logs).resolve("connect");
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, version + "-" + protocol + "-" + role + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = ContainerProcess.start("python", builder);
        return new PythonConnectPeerProcess(process, log);
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY "));
    }

    JsonNode awaitResult() throws Exception {
        String result = this.awaitLine("RESULT ");
        if (!this.process.waitFor(10, TimeUnit.SECONDS) || this.process.exitValue() != 0) {
            throw this.failure("Official Python consumer did not exit successfully");
        }
        return new ObjectMapper().readTree(result);
    }

    private String awaitLine(String prefix) throws Exception {
        // 有界等待可观察的进程信号，提前退出直接报告完整对端日志，不重试业务调用。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            String output = Files.readString(this.log, StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
            if (!this.process.isAlive()) {
                throw this.failure("Official Python peer exited before " + prefix);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw this.failure("Official Python peer timed out waiting for " + prefix);
    }

    private AssertionError failure(String message) throws IOException {
        return new AssertionError(message + "; log: " + this.log + "\n" + Files.readString(this.log));
    }

    @Override
    public void close() throws Exception {
        // 创建者发送 EOF 关闭 WSGI 服务，只回收当前进程，不关闭共享语言容器。
        if (this.process.isAlive()) {
            this.process.getOutputStream().close();
        }
        if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            this.process.waitFor(5, TimeUnit.SECONDS);
            throw this.failure("Official Python peer shutdown timed out");
        }
        if (this.process.exitValue() != 0) {
            throw this.failure("Official Python peer failed");
        }
    }
}
