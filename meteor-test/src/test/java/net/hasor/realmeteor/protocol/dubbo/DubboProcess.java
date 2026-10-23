/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
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

/** 独立官方 JVM 的所有者，供协议与注册中心场景使用相同的真实对端。 */
public final class DubboProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private DubboProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "dubbo-official-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static DubboProcess start(String... arguments) throws IOException {
        return launch(System.getProperty("dubbo.peer.classpath"), List.of(arguments), false);
    }

    public static DubboProcess startLegacy(String... arguments) throws IOException {
        return launch(System.getProperty("dubbo.peer.legacy.classpath"), List.of(arguments), true);
    }

    private static DubboProcess launch(String classpath, List<String> arguments, boolean legacy) throws IOException {
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Official Dubbo peer classpath is not configured");
        }

        // 对端仅加载共享业务模型和官方 SDK，不加载插件生产代码或 Meteor 的 JSON 实现。
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        if (legacy) {
            // 2.7 的 Javassist 代理通过反射定义类，仅对官方旧版测试 JVM 开放 java.lang。
            command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(classpath);
        command.add("net.hasor.peer.protocol.dubbo.DubboPeer");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command) //
                .redirectErrorStream(true) //
                .start();
        return new DubboProcess(process);
    }

    public int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY "));
    }

    public JsonNode awaitResult() throws Exception {
        return JSON.readTree(this.awaitLine("RESULT "));
    }

    long processId() {
        return this.process.pid();
    }

    public void signal(String value) throws IOException {
        // 生命周期场景由父测试推进下一阶段，不重启官方进程或重建它持有的代理。
        byte[] command = (value + "\n").getBytes(StandardCharsets.UTF_8);
        this.process.getOutputStream().write(command);
        this.process.getOutputStream().flush();
    }

    private String awaitLine(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Official Dubbo peer timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Official Dubbo peer stopped unexpectedly:\n" + this.output);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 始终消费子进程日志，真实 EOF 才向等待者发送空标记。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                if (!line.isEmpty()) {
                    this.lines.offer(line);
                }
            }
        } catch (IOException failure) {
            this.output.append(failure).append('\n');
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // EOF 让对端执行 bootstrap.destroy，超时则强制回收，不能遗留监听 JVM。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("Official Dubbo peer shutdown timed out:\n" + this.output);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Official Dubbo peer failed:\n" + this.output);
            }
        } finally {
            // 保留真实官方日志供解码、注册发现和 TLS 失败定位，不用场景断言替代诊断。
            String directory = System.getProperty("dubbo.peer.output.directory", "build/dubbo-peer-output");
            Path outputDirectory = Path.of(directory);
            Files.createDirectories(outputDirectory);
            Path log = outputDirectory.resolve("dubbo-peer-" + this.process.pid() + ".log");
            Files.writeString(log, this.output.toString(), StandardCharsets.UTF_8);
        }
    }
}
