/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.dubbo.go;
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

/** 当前场景拥有一个独立 Dubbo-Go 进程，Compose 继续拥有共享 Go 容器。 */
final class DubboGoPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Process process;
    private final Path ready;
    private final Path result;
    private final Path log;

    private DubboGoPeer(Process process, Path ready, Path result, Path log) {
        this.process = process;
        this.ready = ready;
        this.result = result;
        this.log = log;
    }

    static DubboGoPeer start(String protocol, String role, String target) throws Exception {
        // 依赖与二进制归独立 Go module，沿用每组独占的 Go 日志目录。
        String work = System.getProperty("go.order.peer.work-dir", "build/peer-logs/go-order");
        Path parent = Files.createDirectories(Path.of(work).toAbsolutePath());
        Path directory = Files.createTempDirectory(parent, "dubbo-go-" + protocol + "-" + role + "-");
        Path ready = directory.resolve("ready.txt");
        Path result = directory.resolve("result.json");
        Path log = directory.resolve("peer.log");
        String binary = System.getProperty("go.dubbo.peer.binary", "/opt/meteor-peer/go/dubbo-peer");
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--protocol");
        command.add(protocol);
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
        return new DubboGoPeer(process, ready, result, log);
    }

    int awaitPort() throws Exception {
        // 等待官方 SDK 的监听就绪文件，同时观察进程退出，不能靠固定休眠猜测。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (WatchService changes = this.ready.getFileSystem().newWatchService()) {
            this.ready.getParent().register(changes, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                if (Files.isRegularFile(this.ready)) {
                    return URI.create(Files.readString(this.ready).trim()).getPort();
                }
                if (!this.process.isAlive()) {
                    throw this.failure("Dubbo-Go provider exited before readiness");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw this.failure("Dubbo-Go provider readiness timed out");
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
        // 进程必须正常结束，结果文件不能掩盖 SDK 初始化或真实调用失败。
        if (!this.process.waitFor(25, TimeUnit.SECONDS)) {
            throw this.failure("Dubbo-Go client timed out");
        }
        if (this.process.exitValue() != 0 || !Files.isRegularFile(this.result)) {
            throw this.failure("Dubbo-Go client failed with exit " + this.process.exitValue());
        }
        return JSON.readTree(Files.readString(this.result));
    }

    private AssertionError failure(String message) throws IOException {
        return new AssertionError(message + "\nLog: " + this.log + "\n" + Files.readString(this.log));
    }

    @Override
    public void close() throws Exception {
        // 只终止当前场景创建的进程，保留其它场景与共享语言容器。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                    throw this.failure("Dubbo-Go peer did not terminate");
                }
            }
        }
    }
}
