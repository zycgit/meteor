/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 独立官方 Connect Node 进程的所有者，协议行为由官方依赖实现。 */
final class ConnectNodeProcess implements AutoCloseable {
    private static final ObjectMapper          JSON   = new ObjectMapper();
    private final        Process               process;
    private final        Thread                reader;
    private final        BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final        StringBuffer          output = new StringBuffer();

    private ConnectNodeProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "connect-official-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static ConnectNodeProcess start(String... arguments) throws IOException {
        // 只运行本插件持有的对端脚本及锁定的官方 npm 依赖。
        Path directory = Path.of(System.getProperty("connect.peer.dir"));
        List<String> command = new ArrayList<>(List.of("node", "peer.mjs"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command) //
                .directory(directory.toFile()) //
                .redirectErrorStream(true) //
                .start();
        return new ConnectNodeProcess(process);
    }

    int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY "));
    }

    JsonNode awaitResult() throws Exception {
        String result = this.awaitLine("RESULT ");
        return JSON.readTree(result);
    }

    private String awaitLine(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Official Connect peer timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Official Connect peer stopped unexpectedly:\n" + this.output);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 持续排空子进程日志，进程退出后立即通知有界等待中的测试。
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
        // 关闭 stdin 让官方服务停止接入并释放现有连接，超时后回收整个进程。
        if (this.process.isAlive()) {
            this.process.getOutputStream().close();
        }
        if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            this.process.waitFor(3, TimeUnit.SECONDS);
            throw new AssertionError("Official Connect peer shutdown timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0) {
            throw new AssertionError("Official Connect peer failed:\n" + this.output);
        }
    }
}
