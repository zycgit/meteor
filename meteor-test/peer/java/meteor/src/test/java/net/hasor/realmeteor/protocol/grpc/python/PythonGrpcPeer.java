/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.python;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独占 Python 官方 SDK 进程及其输出读取线程，生命周期不依赖 Meteor。 */
final class PythonGrpcPeer implements AutoCloseable {
    private static final ObjectMapper          MAPPER = new ObjectMapper();
    private final        Process               process;
    private final        Path                  log;
    private final        Thread                reader;
    private final        BlockingQueue<String> lines  = new LinkedBlockingQueue<>();

    private PythonGrpcPeer(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "python-grpc-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static PythonGrpcPeer provider(String security) throws Exception {
        return start("provider", "1.85.0", security, null, null);
    }

    static PythonGrpcPeer consumer(String host, int port, String security, String scenario) throws Exception {
        return start("consumer", "1.85.0", security, host + ":" + port, scenario);
    }

    static PythonGrpcPeer versionProvider(String version) throws Exception {
        return start("provider", version, "clear", null, null);
    }

    static PythonGrpcPeer versionConsumer(String version, String host, int port) throws Exception {
        return start("consumer", version, "clear", host + ":" + port, "unicode");
    }

    int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY ", 10));
    }

    JsonNode awaitResult() throws Exception {
        return MAPPER.readTree(this.awaitLine("RESULT ", 20));
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    long processId() {
        return this.process.pid();
    }

    private static PythonGrpcPeer start(String role, String version, String security, String target,
            String scenario) throws Exception {
        // SDK 和生成契约仅通过独立 Python 解释器加载，不进入 Java classpath。
        List<String> command = new ArrayList<>();
        Path peers = Path.of(System.getProperty("meteor.test.peerDirectory", "peer"));
        Path script = peers.resolve("python/grpc/peer.py");
        command.add("/opt/meteor-peer/python-grpc-" + version + "/bin/python");
        command.add(System.getProperty("python.grpc.script", script.toString()));
        command.add(role);
        command.add("--sdk-version");
        command.add(version);
        command.add("--generated");
        command.add("/opt/meteor-peer/python-grpc-generated");
        command.add("--security");
        command.add(security);
        command.add("--certificates");
        var certificate = PythonGrpcPeer.class.getResource("/certs/acceptance/ca.pem");
        command.add(Path.of(certificate.toURI()).getParent().toString());
        if (target != null) {
            command.add("--target");
            command.add(target);
            command.add("--scenario");
            command.add(scenario);
        }

        // 每次启动都保留独立日志，读取线程持续排空 stdout，避免子进程被缓冲区阻塞。
        Path directory = Path.of(System.getProperty("python.grpc.logs", "build/python-grpc-peer-logs"));
        Files.createDirectories(directory);
        Path log = directory.resolve(role + "-" + UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("python", builder);
        return new PythonGrpcPeer(process, log);
    }

    private String awaitLine(String prefix, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Python gRPC peer timed out; log: " + this.log);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Python gRPC peer did not return " + prefix + "; log: " + this.log);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input); //
             BufferedWriter output = Files.newBufferedWriter(this.log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write(line);
                output.newLine();
                output.flush();
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.lines.offer("ERROR " + failure);
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 关闭父进程的输入管道，通知 provider 停止其 SDK server 与工作线程。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Python gRPC peer did not close; log: " + this.log);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Python gRPC peer failed; log: " + this.log);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            // 正常等待、日志读取或线程中断失败时，也只回收自己创建的子进程。
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
            }
        }
    }
}
