/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.registry;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.example.Order;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 显式发现适配后的原生 Node SDK；stdio 只交付地址和场景数据，不代发业务 RPC。 */
final class JsonRpcRegistryNodePeer implements AutoCloseable {
    private static final ObjectMapper          JSON  = new ObjectMapper();
    private final        Process               process;
    private final        Path                  log;
    private final        Thread                reader;
    private final        BufferedWriter        commands;
    private final        BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private JsonRpcRegistryNodePeer(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new Thread(this::readOutput, "jsonrpc-node-registry-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static JsonRpcRegistryNodePeer provider() throws Exception {
        return start("provider");
    }

    static JsonRpcRegistryNodePeer consumer() throws Exception {
        JsonRpcRegistryNodePeer peer = start("consumer");
        try {
            peer.awaitLine("CONSUMER_READY", 10);
            return peer;
        } catch (Exception | Error failure) {
            peer.close();
            throw failure;
        }
    }

    private static JsonRpcRegistryNodePeer start(String role) throws Exception {
        // 对端依赖沿用已有精确锁定的 Jayson，新增脚本不改变既有直连对端。
        Path directory = Path.of(System.getProperty("jsonrpc.peer.dir", "/opt/meteor-peer/node/jsonrpc"));
        Path logs = Path.of("build/peer-logs/node-jsonrpc-registry");
        Files.createDirectories(logs);
        Path log = Files.createTempFile(logs, role + "-", ".log");
        Path script = directory.resolve("order-registry-peer.js");
        ProcessBuilder builder = new ProcessBuilder("node", script.toString(), role) //
                .directory(directory.toFile()).redirectErrorStream(true);
        Process process = ContainerProcess.start("node", builder);
        return new JsonRpcRegistryNodePeer(process, log);
    }

    long processId() {
        return this.process.pid();
    }

    InterAddress awaitAddress(String unit) throws Exception {
        int port = Integer.parseInt(this.awaitLine("READY:", 10));
        return new InterAddress("http", ContainerProcess.host(this.process), port, unit);
    }

    Order roundTrip(URI resolvedAddress, Order expected) throws Exception {
        // 目标业务地址来自 resolver 的正式存储查询与订阅，Node 启动时没有固定 URL。
        ObjectNode command = JSON.createObjectNode();
        command.put("address", resolvedAddress.toString());
        command.set("order", JSON.valueToTree(expected));
        this.commands.write(JSON.writeValueAsString(command));
        this.commands.newLine();
        this.commands.flush();
        String result = this.awaitLine("RESULT:", 15);
        return JSON.readValue(result, Order.class);
    }

    boolean isAlive() {
        return this.process.isAlive();
    }

    private String awaitLine(String prefix, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Node registry adapter timed out; log: " + this.log);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Node registry adapter exited before " + prefix + "; log: " + this.log);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 日志持续排空并落盘，标准 SDK 的错误不会阻塞在 stdout 管道中。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader source = new BufferedReader(input); //
             BufferedWriter target = Files.newBufferedWriter(this.log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = source.readLine()) != null) {
                target.write(line);
                target.newLine();
                target.flush();
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.lines.offer("ERROR:" + failure);
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者关闭命令输入后，Node SDK 释放自己的监听或退出消费者读取循环。
        try {
            this.commands.close();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Node registry peer did not close; log: " + this.log);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Node registry peer failed; log: " + this.log);
            }
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
