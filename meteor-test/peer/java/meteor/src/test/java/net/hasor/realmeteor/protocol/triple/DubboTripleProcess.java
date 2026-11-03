/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
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
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.protocol.support.JavaSdkPeerUtils;

/** 独立官方 Dubbo Java 进程的所有者，协议行为由官方依赖实现。 */
final class DubboTripleProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private DubboTripleProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "triple-official-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static DubboTripleProcess start(String... arguments) throws IOException {
        // 独立 JVM 只加载官方 Dubbo 依赖和互通示例，不加载 Meteor 生产依赖。
        return launch(JavaSdkPeerUtils.classpath("dubbo"), List.of(arguments));
    }

    static DubboTripleProcess startLegacy(String... arguments) throws IOException {
        // 3.2.20 的原版服务端正确处理压缩首帧，用独立依赖验证出站 gzip。
        List<String> legacyArguments = new ArrayList<>(List.of(arguments));
        legacyArguments.add("--legacy=true");
        return launch(JavaSdkPeerUtils.classpath("tripleLegacy"), legacyArguments);
    }

    private static DubboTripleProcess launch(String classpath, List<String> arguments) throws IOException {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        String mainClass = "net.hasor.peer.protocol.triple.DubboTriplePeer";
        // 独立对端完成场景后的 SDK 优雅退出等待限定为一秒。
        List<String> command = new ArrayList<>(List.of(executable.toString(), //
                "-Ddubbo.service.shutdown.wait=1000", "-cp", classpath, mainClass));
        command.addAll(arguments);
        boolean hasHost = arguments.stream().anyMatch(value -> value.startsWith("--host="));
        if (!hasHost) {
            // 官方消费者通过桥接网络访问 Meteor，不使用自身容器的 loopback。
            command.add("--host=" + ContainerProcess.runnerHost());
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("java", builder);
        return new DubboTripleProcess(process);
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY "));
    }

    JsonNode awaitResult() throws Exception {
        String result = this.awaitLine("RESULT ");
        return JSON.readTree(result);
    }

    long processId() {
        return this.process.pid();
    }

    private String awaitLine(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
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
        // 持续排空子进程日志，进程退出后立即通知有界等待中的测试。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                // 空日志行只保留在诊断内容中，空队列标记专门表示真实 EOF。
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
        // 关闭 stdin 让官方服务停止接入并释放现有连接，超时后回收整个进程。
        boolean interrupted = Thread.interrupted();
        try {
            if (interrupted) {
                throw new InterruptedException("Interrupted while closing the official Dubbo peer");
            }
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            if (!this.process.waitFor(8, TimeUnit.SECONDS)) {
                throw new AssertionError("Official Dubbo peer shutdown timed out:\n" + this.output);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("Official Dubbo peer failed:\n" + this.output);
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            throw failure;
        } finally {
            // 正常关闭、失败和中断都必须回收本次创建的对端，不将强制终止当作成功。
            boolean terminated = true;
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                terminated = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!terminated) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        terminated = this.process.waitFor(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!terminated) {
                throw new AssertionError("Official Dubbo peer could not be terminated:\n" + this.output);
            }
        }
    }
}
