/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;

/** 独立官方 Thrift JVM；有界等待并持久保留每个原生对端的完整输出。 */
final class ThriftPeerProcess implements AutoCloseable {
    private final Process               process;
    private final Thread                reader;
    private final Path                  log;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private ThriftPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "thrift-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static ThriftPeerProcess start(String... arguments) throws IOException {
        // 子进程复用标准测试 class 目录，依赖仅限官方 SDK，不继承完整测试运行类路径。
        String classpath = System.getProperty("thrift.peer.classpath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("Official Thrift peer classpath is not configured");
        }
        String configuredOutput = System.getProperty("thrift.peer.output.directory", "build/reports/thrift-peer");
        Path reportDirectory = Path.of(configuredOutput).toAbsolutePath();
        Files.createDirectories(reportDirectory);
        Path log = Files.createTempFile(reportDirectory, "native-", ".log");
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-cp", classpath));
        command.add("net.hasor.peer.protocol.thrift.ThriftPeer");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new ThriftPeerProcess(process, log);
    }

    int awaitPort() throws InterruptedException {
        // 输出线程唤醒等待方，启动失败不阻塞在子进程标准输出上。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Official Thrift peer startup timed out");
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Official Thrift peer exited before listening");
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    void requireResult(String expected) throws InterruptedException {
        // 一次原生客户端进程只执行一个场景；退出码和明确结果必须同时成立。
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw this.failure("Official Thrift peer call timed out");
        }
        this.reader.join(1000);
        String marker = "RESULT " + expected + "\n";
        if (this.process.exitValue() != 0 || !this.output.toString().contains(marker)) {
            throw this.failure("Official Thrift peer did not produce " + expected);
        }
    }

    void requireFailure(String marker) throws InterruptedException {
        // 证书等拒绝必须使官方客户端明确失败，不能把等待超时当作预期的失败。
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw this.failure("Official Thrift peer failure timed out");
        }
        this.reader.join(1000);
        String output = this.output.toString();
        boolean successfulResult = output.contains("RESULT ");
        if (this.process.exitValue() == 0 || successfulResult || !output.contains(marker)) {
            throw this.failure("Official Thrift peer did not fail with " + marker);
        }
    }

    Path logPath() {
        return this.log;
    }

    long processId() {
        return this.process.pid();
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; native log: " + this.log + "\n" + this.output);
    }

    private void readOutput() {
        // 持续排空合并后的输出，逐行落盘，失败时仍能回看原生协议栈日志。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                Files.writeString(this.log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
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
        // 服务端读取 stdin EOF 后正常关闭；所有失败路径也回收本对象创建的 JVM。
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
