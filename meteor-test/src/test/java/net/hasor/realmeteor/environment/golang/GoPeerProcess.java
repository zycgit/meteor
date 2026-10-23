/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.golang;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 拥有独立 Go 对端进程和控制文件；业务 RPC 完全由 Go 官方客户端、服务端完成。 */
public final class GoPeerProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final Path    ready;
    private final Path    result;
    private final Path    log;

    private GoPeerProcess(Process process, Path ready, Path result, Path log) {
        this.process = process;
        this.ready = ready;
        this.result = result;
        this.log = log;
    }

    public static GoPeerProcess start(String protocol, String role, String tlsMode, String scenario, String target)
            throws Exception {
        String executable = System.getProperty("go.order.peer.binary");
        if (executable == null || executable.isBlank()) {
            throw new IOException("Missing system property: go.order.peer.binary");
        }
        Path binary = Path.of(executable).toAbsolutePath();
        if (!Files.isExecutable(binary)) {
            throw new IOException("Go order peer is not executable: " + binary);
        }
        String workDirectory = System.getProperty("go.order.peer.work-dir", "build/peer-logs/go-order");
        Path logs = Files.createDirectories(Path.of(workDirectory).toAbsolutePath());
        Path directory = Files.createTempDirectory(logs, protocol + "-" + role + "-" + tlsMode + "-" + scenario + "-");
        Path ready = directory.resolve("ready.txt");
        Path result = directory.resolve("result.json");
        Path log = directory.resolve("peer.log");

        // 顺序固定为程序、协议、角色、安全模式、场景、端点和控制文件，日志保留完整启动参数。
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.add("--protocol");
        command.add(protocol);
        command.add("--role");
        command.add(role);
        command.add("--tls-mode");
        command.add(tlsMode);
        command.add("--scenario");
        command.add(scenario);
        if (target != null) {
            command.add("--target");
            command.add(target);
        }
        command.add("--ready-file");
        command.add(ready.toString());
        command.add("--result-file");
        command.add(result.toString());
        command.add("--cert-dir");
        command.add(certificateDirectory().toString());
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new GoPeerProcess(process, ready, result, log);
    }

    public static Path certificateDirectory() throws Exception {
        String configured = System.getProperty("go.order.peer.cert-dir");
        if (configured != null && !configured.isBlank()) {
            Path directory = Path.of(configured).toAbsolutePath();
            if (!Files.isRegularFile(directory.resolve("ca.pem"))) {
                throw new IOException("Missing Go peer CA certificate: " + directory);
            }
            return directory;
        }
        URL certificate = GoPeerProcess.class.getResource("/grpc/tls/acceptance/ca.pem");
        if (certificate == null) {
            throw new IOException("Missing public Go peer certificates: /grpc/tls/acceptance/ca.pem");
        }
        return Path.of(certificate.toURI()).getParent().toAbsolutePath();
    }

    public String awaitReady() throws Exception {
        // 观察 Go 写出的真实监听地址，避免预先分配端口或用固定休眠猜测服务就绪。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (WatchService changes = this.ready.getFileSystem().newWatchService()) {
            this.ready.getParent().register(changes, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                if (Files.exists(this.ready)) {
                    String target = Files.readString(this.ready).trim();
                    if (!target.isEmpty()) {
                        return target;
                    }
                }
                if (!this.process.isAlive()) {
                    throw this.failure("Go order server exited before announcing readiness");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw this.failure("Go order server readiness timed out");
                }
                WatchKey change = changes.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
                if (change != null) {
                    change.pollEvents();
                    change.reset();
                }
            }
        }
    }

    public JsonNode awaitResult() throws Exception {
        // 先等待客户端正常退出，再读取已经关闭的结果文件，非零退出不能被成功 JSON 掩盖。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw this.failure("Go order client timed out");
        }
        if (this.process.exitValue() != 0) {
            throw this.failure("Go order client exited with status " + this.process.exitValue());
        }
        if (!Files.isRegularFile(this.result)) {
            throw this.failure("Go order client did not write its result");
        }
        return JSON.readTree(Files.readString(this.result));
    }

    public long processId() {
        return this.process.pid();
    }

    private AssertionError failure(String message) throws IOException {
        return new AssertionError(message + "\nLog: " + this.log + "\n" + Files.readString(this.log));
    }

    @Override
    public void close() throws Exception {
        // 创建者结束自己启动的 Go 进程；服务端的主动终止不按客户端断言失败处理。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                    throw this.failure("Go order peer did not terminate");
                }
            }
        }
    }
}
