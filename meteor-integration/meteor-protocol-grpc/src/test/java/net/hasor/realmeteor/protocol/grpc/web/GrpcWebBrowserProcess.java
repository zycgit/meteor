/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 独立浏览器进程的所有者，启动、调用和关闭都有等待上限。 */
final class GrpcWebBrowserProcess implements AutoCloseable {
    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();
    private final ObjectMapper          json   = new ObjectMapper();

    private GrpcWebBrowserProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "grpc-web-browser-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static GrpcWebBrowserProcess start(String... arguments) throws IOException {
        // 依赖和官方客户端仅从本插件测试工作目录加载。
        Path directory = Path.of(System.getProperty("grpcweb.peer.dir"));
        List<String> command = new ArrayList<>(List.of("node", "browser.js"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command) //
                .directory(directory.toFile()) //
                .redirectErrorStream(true) //
                .start();
        return new GrpcWebBrowserProcess(process);
    }

    String awaitOrigin() throws InterruptedException {
        return this.awaitLine("READY ", 15);
    }

    JsonNode invoke(String target, String scene) throws Exception {
        // 页面先准备独立 origin，Meteor 启动后再交付调用地址与单个场景。
        String command = this.json.writeValueAsString(Map.of("target", target, "scene", scene)) + "\n";
        this.process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
        this.process.getOutputStream().flush();
        String result = this.awaitLine("RESULT ", 20);

        // 浏览器保持在线供调用方验证取消传播，最终由 close 负责释放并检查正常退出。
        return this.json.readTree(result);
    }

    private String awaitLine(String prefix, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("gRPC-Web browser timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("gRPC-Web browser stopped unexpectedly:\n" + this.output);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 独立读取线程持续排空日志，结束标记立即唤醒等待调用方。
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
        // 正常关闭 stdin 后由 Node 释放 Chromium；异常时一并回收其子进程。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
        } finally {
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                List<ProcessHandle> children = this.process.descendants().toList();
                for (ProcessHandle child : children) {
                    child.destroyForcibly();
                }
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
                throw new AssertionError("gRPC-Web browser shutdown timed out:\n" + this.output);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("gRPC-Web browser failed:\n" + this.output);
            }
        }
    }
}
