/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.oncrpc.go;
import java.io.IOException;
import java.net.URI;
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

/** 拥有当前场景的独立 Go SunRPC 源码 SDK 进程，不管理共享语言容器。 */
final class GoOncRpcPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Process process;
    private final Path ready;
    private final Path result;
    private final Path log;

    private GoOncRpcPeer(Process process, Path ready, Path result, Path log) {
        this.process = process;
        this.ready = ready;
        this.result = result;
        this.log = log;
    }

    static GoOncRpcPeer start(String role, String target) throws Exception {
        // 源码 SDK 使用独立 module 和二进制；控制文件沿用每组隔离的 Go 日志目录。
        String work = System.getProperty("go.order.peer.work-dir", "build/peer-logs/go-order");
        Path parent = Files.createDirectories(Path.of(work).toAbsolutePath());
        Path directory = Files.createTempDirectory(parent, "go-oncrpc-" + role + "-");
        Path ready = directory.resolve("ready.txt");
        Path result = directory.resolve("result.json");
        Path log = directory.resolve("peer.log");
        String binary = System.getProperty("go.oncrpc.peer.binary", "/opt/meteor-peer/go/oncrpc-peer");
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--role");
        command.add(role);
        command.add("--ready-file");
        command.add(ready.toString());
        command.add("--result-file");
        command.add(result.toString());
        if (target != null) {
            command.add("--target");
            command.add(target);
        }
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = ContainerProcess.start("go", builder);
        return new GoOncRpcPeer(process, ready, result, log);
    }

    int awaitPort() throws Exception {
        // 监听器绑定后才发布就绪文件；等待期间同时检查 SDK 进程是否提前退出。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (WatchService changes = this.ready.getFileSystem().newWatchService()) {
            this.ready.getParent().register(changes, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                if (Files.isRegularFile(this.ready)) {
                    return URI.create(Files.readString(this.ready).trim()).getPort();
                }
                if (!this.process.isAlive()) {
                    throw this.failure("Go SunRPC provider exited before readiness");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw this.failure("Go SunRPC provider readiness timed out");
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

    String host() {
        return ContainerProcess.host(this.process);
    }

    JsonNode awaitResult() throws Exception {
        // 结果必须来自本次成功退出的客户端，初始化失败和网络异常保留原始日志。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw this.failure("Go SunRPC client timed out");
        }
        if (this.process.exitValue() != 0 || !Files.isRegularFile(this.result)) {
            throw this.failure("Go SunRPC client failed with exit " + this.process.exitValue());
        }
        return JSON.readTree(Files.readString(this.result));
    }

    private AssertionError failure(String message) throws IOException {
        return new AssertionError(message + "\nLog: " + this.log + "\n" + Files.readString(this.log));
    }

    @Override
    public void close() throws Exception {
        // 只结束当前测试创建的进程，Compose 继续拥有共享 Go 容器。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                    throw this.failure("Go SunRPC peer did not terminate");
                }
            }
        }
    }
}
