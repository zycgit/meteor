/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.environment.node;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 拥有基础 Node SDK 互通场景的独立进程，业务调用由对应 SDK 执行。 */
public final class NodeRpcPeer implements AutoCloseable {
    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private NodeRpcPeer(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "node-rpc-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static NodeRpcPeer start(String protocol, String... arguments) throws IOException {
        // 只借用已经安装锁定 SDK 的语言容器，每个用例拥有自己的进程和监听端口。
        String script = "/opt/meteor-peer/node/" + protocol + "/peer.js";
        return launch(script, arguments);
    }

    public static NodeRpcPeer startVersion(String profile, String... arguments) throws IOException {
        // 版本选择归当前进程，不修改并发测试共享的系统属性或默认 SDK 目录。
        String script = "/opt/meteor-peer/sdk-versions/" + profile + "/run.cjs";
        return launch(script, arguments);
    }

    public static List<Object[]> compatibilityCases(String protocol) throws IOException {
        // 清单由准备阶段读取实际安装版本生成，参数名称与实际 SDK 图保持一致。
        Path manifest = Path.of("/opt/meteor-peer/sdk-versions/runtimes.json");
        JsonNode profiles = new ObjectMapper().readTree(Files.readString(manifest)).required("profiles");
        List<Object[]> cases = new ArrayList<>();
        for (JsonNode profile : profiles) {
            if (!StringUtils.equals(protocol, profile.required("protocol").textValue())) {
                continue;
            }
            for (JsonNode role : profile.required("roles")) {
                String direction = "Meteor-to-SDK";
                if (StringUtils.equals("consumer", role.textValue())) {
                    direction = "SDK-to-Meteor";
                }
                cases.add(new Object[] {profile.required("sdk").textValue(), profile.required("version").textValue(),
                        profile.required("profile").textValue(), direction});
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("No installed Node SDK versions for " + protocol);
        }
        return cases;
    }

    private static NodeRpcPeer launch(String script, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("node", script));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        return new NodeRpcPeer(ContainerProcess.start("node", builder));
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public int awaitPort() throws InterruptedException {
        // 持续读取日志并限定启动时间，进程提前退出时立即报告完整输出。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Node RPC provider startup timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Node RPC provider stopped before listening:\n" + this.output);
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    public void requireSuccess() throws InterruptedException {
        // SDK 消费者必须完成真实调用并自行验证结果，成功标记不能替代退出码检查。
        String result = this.awaitOutput();
        if (!result.contains("OK\n")) {
            throw new AssertionError("Node RPC consumer returned no success marker:\n" + result);
        }
    }

    public JsonNode requireResult() throws InterruptedException, IOException {
        // 订单版本场景由 Java 断言返回内容，不能将进程正常结束当成数据互通。
        String result = this.awaitOutput();
        List<String> records = result.lines().filter(line -> line.startsWith("{")).toList();
        if (records.isEmpty()) {
            throw new AssertionError("Node RPC consumer returned no order:\n" + result);
        }
        return new ObjectMapper().readTree(records.get(records.size() - 1));
    }

    private String awaitOutput() throws InterruptedException {
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new AssertionError("Node RPC consumer timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0) {
            throw new AssertionError("Node RPC consumer failed:\n" + this.output);
        }
        return this.output.toString();
    }

    private void readOutput() {
        // 输出线程始终排空管道；EOF 标记唤醒仍在等待启动结果的调用者。
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
        try {
            // EOF 请求 SDK 释放监听与连接；不能关闭借入的常驻语言容器。
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Node RPC peer shutdown timed out:\n" + this.output);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Node RPC peer failed:\n" + this.output);
            }
        } finally {
            // 异常路径仍回收本用例拥有的进程树，关闭超时保持失败结果。
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
