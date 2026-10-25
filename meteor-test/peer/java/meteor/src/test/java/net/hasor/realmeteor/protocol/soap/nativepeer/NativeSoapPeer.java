/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.soap.nativepeer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 借用 native 容器，只拥有本场景的 gSOAP C 或 C++ 进程。 */
final class NativeSoapPeer implements AutoCloseable {
    private final Process process;
    private final Thread reader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuffer output = new StringBuffer();

    private NativeSoapPeer(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "gsoap-native-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static NativeSoapPeer startExecutable(String executable, String... arguments) throws IOException {
        // 版本场景直接选择已编译入口，进程生命周期仍归本场景持有者。
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        return new NativeSoapPeer(ContainerProcess.start("native", builder));
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    int awaitPort() throws InterruptedException {
        // READY 来自实际 bind 后的端口；提前退出和启动超时均保留原始输出。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("gSOAP native provider startup timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("gSOAP native provider did not become ready:\n" + this.output);
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    void requireSuccess() throws InterruptedException {
        // SDK 客户端同时校验 echo 响应和进程退出码，不能以连接成功代替互通。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new AssertionError("gSOAP native consumer timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0 || !this.output.toString().contains("OK\n")) {
            throw new AssertionError("gSOAP native consumer failed:\n" + this.output);
        }
    }

    private void readOutput() {
        // 始终排空 SDK 标准输出，容器执行框架另行归档完整日志。
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
            // stdin EOF 请求原生服务释放监听；不结束共享语言容器。
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("gSOAP native peer shutdown timed out:\n" + this.output);
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw new AssertionError("gSOAP native peer failed:\n" + this.output);
            }
        } finally {
            // 异常时仍回收本场景进程树，保留原始失败。
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
