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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Connect 与 gRPC-Web 的 Java SDK 进程所有者，分别接入客户端结果与服务端就绪信号。 */
public final class ConnectJavaProcess implements AutoCloseable {
    private final Process process;
    private final Thread reader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> outputCompleted = new CompletableFuture<>();
    private final StringBuffer output = new StringBuffer();

    private ConnectJavaProcess(Process process) {
        this.process = process;
        this.output.append("PID ").append(process.pid()).append('\n');
        this.reader = new Thread(this::readOutput, "connect-java-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static ConnectJavaProcess start(String protocol, String url, String method, String value) throws IOException {
        // main 参数顺序固定为协议、地址、方法和文本，与已有客户端入口一致。
        List<String> arguments = new ArrayList<>();
        arguments.add(protocol);
        arguments.add(url);
        arguments.add(method);
        arguments.add(value);
        String peerClass = "net.hasor.peer.protocol.connect.ConnectJavaPeer";
        return launch("connect.java.peer.classpath", peerClass, arguments);
    }

    public static ConnectJavaProcess startServer() throws IOException {
        return launch("connect.java.server.peer.classpath", //
                "net.hasor.peer.protocol.connect.ConnectJavaServerPeer", List.of());
    }

    private static ConnectJavaProcess launch(String classpathProperty, String mainClass, List<String> arguments) //
            throws IOException {
        String classpath = System.getProperty(classpathProperty);
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Connect Java peer classpath is not configured: " + classpathProperty);
        }

        // 依赖图由 Gradle 交付；服务端的 Protobuf 4 图不进入宿主主测试运行类路径。
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new ConnectJavaProcess(process);
    }

    public String awaitResult() throws Exception {
        String result = awaitLine("RESULT ", 15);
        byte[] bytes = Base64.getDecoder().decode(result);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public int awaitPort() throws Exception {
        int port = Integer.parseInt(awaitLine("READY ", 20));
        if (port < 1 || port > 65535) {
            throw new AssertionError("Connect Java server returned an invalid port: " + port);
        }
        return port;
    }

    private String awaitLine(String prefix, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Connect Java peer timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("Connect Java peer stopped unexpectedly:\n" + this.output);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private void readOutput() {
        // 持续排空标准输出和错误；READY 之后的读取失败也必须使场景失败。
        var input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader buffered = new BufferedReader(input)) {
            String line;
            while ((line = buffered.readLine()) != null) {
                this.output.append(line).append('\n');
                if (!line.isEmpty()) {
                    this.lines.offer(line);
                }
            }
        } catch (IOException failure) {
            this.output.append(failure).append('\n');
            this.outputCompleted.completeExceptionally(failure);
            return;
        } finally {
            this.lines.offer("");
        }
        this.outputCompleted.complete(null);
    }

    @Override
    public void close() throws Exception {
        try {
            // EOF 停止服务端；客户端正常退出后关闭 stdin 也不影响已有调用行为。
            this.process.getOutputStream().close();
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Connect Java peer shutdown timed out:\n" + this.output);
            }
            this.outputCompleted.get(5, TimeUnit.SECONDS);
            this.reader.join(1000);
            if (this.reader.isAlive()) {
                throw new AssertionError("Connect Java output reader did not stop:\n" + this.output);
            }
            this.output.append("EXIT ").append(this.process.exitValue()).append('\n');
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Connect Java peer failed:\n" + this.output);
            }
        } finally {
            // 无论启动、调用或退出在哪一步失败，都回收自建进程并保存本轮输出。
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
                this.reader.join(1000);
            }
            String runDirectory = System.getProperty("meteor.test.runDirectory", "build/connect-java-output");
            Path directory = Path.of(runDirectory, "connect-java-peer-logs");
            Files.createDirectories(directory);
            Path log = directory.resolve("peer-" + this.process.pid() + ".log");
            Files.writeString(log, this.output.toString(), StandardCharsets.UTF_8);
        }
    }
}
