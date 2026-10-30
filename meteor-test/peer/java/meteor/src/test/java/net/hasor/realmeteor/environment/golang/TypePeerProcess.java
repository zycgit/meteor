/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.golang;
import java.io.IOException;
import java.net.URI;
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
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 拥有独立 Go 类型对端进程和控制文件；业务 RPC 完全由 Go 官方客户端、服务端完成。 */
public final class TypePeerProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final Path    ready;
    private final Path    result;
    private final Path    log;

    private TypePeerProcess(Process process, Path ready, Path result, Path log) {
        this.process = process;
        this.ready = ready;
        this.result = result;
        this.log = log;
    }

    public static TypePeerProcess start(String protocol, String role, String vector, String target) throws Exception {
        // 独立镜像中的官方 SDK 对端只执行当前数据类型向量，不依赖宿主 Go 工具链。
        String executable = System.getProperty("go.types.peer.binary", "/opt/meteor-peer/go/types-peer");
        Path logs = Files.createDirectories(Path.of("build/peer-logs/go-types").toAbsolutePath());
        Path directory = Files.createTempDirectory(logs, protocol + "-" + role + "-" + vector + "-");
        Path ready = directory.resolve("ready.txt");
        Path result = directory.resolve("result.json");
        Path log = directory.resolve("peer.log");
        URL fixture = TypePeerProcess.class.getResource("/data/types/values.json");
        if (fixture == null) {
            throw new IOException("Missing shared type vectors");
        }

        // 顺序固定为 SDK 程序、协议、角色、向量、目标地址和控制文件。
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--protocol");
        command.add(protocol);
        command.add("--role");
        command.add(role);
        command.add("--vector");
        command.add(vector);
        if (target != null) {
            command.add("--target");
            command.add(target);
        }
        command.add("--ready-file");
        command.add(ready.toString());
        command.add("--result-file");
        command.add(result.toString());
        command.add("--values-file");
        command.add(Path.of(fixture.toURI()).toAbsolutePath().toString());
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = ContainerProcess.start("go", builder);
        return new TypePeerProcess(process, ready, result, log);
    }

    public String awaitReady() throws Exception {
        // 观察 Go 写出的真实监听地址，避免预先分配端口或用固定休眠猜测服务就绪。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (WatchService changes = this.ready.getFileSystem().newWatchService()) {
            Path parent = this.ready.getParent();
            parent.register(changes, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                if (Files.exists(this.ready)) {
                    String target = Files.readString(this.ready).trim();
                    if (!target.isEmpty()) {
                        URI announced = URI.create(target.contains("://") ? target : "tcp://" + target);
                        String authority = ContainerProcess.host(this.process) + ":" + announced.getPort();
                        if (target.contains("://")) {
                            return announced.getScheme() + "://" + authority;
                        }
                        return authority;
                    }
                }
                if (!this.process.isAlive()) {
                    throw this.failure("Go type server exited before announcing readiness");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw this.failure("Go type server readiness timed out");
                }
                long pause = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100));
                WatchKey change = changes.poll(pause, TimeUnit.NANOSECONDS);
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
            throw this.failure("Go type client timed out");
        }
        if (this.process.exitValue() != 0) {
            throw this.failure("Go type client exited with status " + this.process.exitValue());
        }
        if (!Files.isRegularFile(this.result)) {
            throw this.failure("Go type client did not write its result");
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
                    throw this.failure("Go type peer did not terminate");
                }
            }
        }
    }
}
