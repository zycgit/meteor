/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.types;
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
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 类型场景专用的官方 Node/浏览器对端所有者，控制管道不转发业务 RPC。 */
public final class TypeNodePeerProcess implements AutoCloseable {
    private static final ObjectMapper          JSON   = new ObjectMapper();
    private final        Process               process;
    private final        Thread                reader;
    private final        BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final        StringBuffer          output = new StringBuffer();

    private TypeNodePeerProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "type-node-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static TypeNodePeerProcess connect(String role, String format) throws IOException {
        return start("node", "connect", "types-peer.mjs", List.of(role, format));
    }

    public static TypeNodePeerProcess browser() throws IOException {
        return start("browser", "grpcweb", "types-browser.js", List.of());
    }

    private static TypeNodePeerProcess start(String runtime, String directory, String script, List<String> arguments) throws IOException {
        // 依赖和 SDK 由锁定镜像提供，语言进程与 JUnit 使用独立的容器网络空间。
        List<String> command = new ArrayList<>();
        command.add("node");
        command.add(script);
        command.addAll(arguments);
        Path workDirectory = Path.of("/opt/meteor-peer/node", directory);
        ProcessBuilder builder = new ProcessBuilder(command) //
                .directory(workDirectory.toFile()).redirectErrorStream(true);
        Path vectors = Path.of("src/test/resources/vectors/types/values.json").toAbsolutePath();
        builder.environment().put("METEOR_TYPE_VECTORS", vectors.toString());
        return new TypeNodePeerProcess(ContainerProcess.start(runtime, builder));
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public JsonNode awaitReady() throws Exception {
        return JSON.readTree(this.awaitLine("READY "));
    }

    public JsonNode invoke(Map<String, String> command) throws Exception {
        // 仅发送目标和向量标识，真实消息由对端在自己的语言环境恢复并经过 SDK 编解码。
        byte[] bytes = (JSON.writeValueAsString(command) + "\n").getBytes(StandardCharsets.UTF_8);
        this.process.getOutputStream().write(bytes);
        this.process.getOutputStream().flush();
        return JSON.readTree(this.awaitLine("RESULT "));
    }

    private String awaitLine(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Type peer timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Type peer stopped unexpectedly:\n" + this.output);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 独立读取线程持续排空容器输出，并让异常退出立即唤醒等待者。
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
        // stdin 关闭通知对端释放它拥有的监听、HTTP Agent 或 Chromium，再回收容器。
        if (this.process.isAlive()) {
            this.process.getOutputStream().close();
        }
        if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            this.process.waitFor(3, TimeUnit.SECONDS);
            throw new AssertionError("Type peer shutdown timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0) {
            throw new AssertionError("Type peer failed:\n" + this.output);
        }
    }
}
