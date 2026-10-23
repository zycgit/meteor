/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 独立 Node.js 对端的启动、输出和关闭均有明确等待上限。 */
final class JsonRpcPeerProcess implements AutoCloseable {
    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private JsonRpcPeerProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "jsonrpc-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static JsonRpcPeerProcess start(String... arguments) throws IOException {
        // 仅从 Gradle 已安装的独立对端目录加载依赖，错误输出一并保留。
        Path directory = Path.of(System.getProperty("jsonrpc.peer.dir"));
        List<String> command = new ArrayList<>(List.of("node", "peer.js"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command) //
                .directory(directory.toFile()) //
                .redirectErrorStream(true) //
                .start();
        return new JsonRpcPeerProcess(process);
    }

    int awaitPort() throws InterruptedException {
        // 标准输出由独立线程读取，启动失败不会卡在 BufferedReader.readLine。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("JSON-RPC peer startup timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("JSON-RPC peer exited before listening:\n" + this.output);
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    void requireSuccess() throws InterruptedException {
        // 客户端场景必须正常退出并输出成功标记，超时不会当作通知成功。
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw new AssertionError("JSON-RPC peer scenario timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0 || !this.output.toString().contains("OK\n")) {
            throw new AssertionError("JSON-RPC peer scenario failed:\n" + this.output);
        }
    }

    private void readOutput() {
        // 持续排空输出，避免子进程管道占满；最后的空标记唤醒启动等待。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.output.append(failure).append('\n');
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 正常关闭服务端 stdin；失败或超时仍由创建者终止并等待子进程回收。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
        } finally {
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
