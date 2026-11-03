/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.protocol.support.JavaSdkPeerUtils;

/** 独立 Caucho JVM 的进程所有者；场景断言仍保留在 JUnit 方法中。 */
final class CauchoHessianPeerProcess implements AutoCloseable {
    private final Process               process;
    private final Path                  log;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();
    private       int                   port;

    private CauchoHessianPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "hessian-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static CauchoHessianPeerProcess startServer() throws Exception {
        // 只有进程真正监听后才把地址交给 Meteor 消费者。
        CauchoHessianPeerProcess peer = start(List.of("server"));
        try {
            peer.port = peer.awaitPort();
            return peer;
        } catch (Throwable failure) {
            peer.close();
            throw failure;
        }
    }

    static HessianInteropDemo client(int version, String address) {
        // JDK 代理仅转交本地控制数据；网络 Hessian 调用完全在原生 SDK 进程中执行。
        return (HessianInteropDemo) Proxy.newProxyInstance(HessianInteropDemo.class.getClassLoader(), //
                new Class<?>[] { HessianInteropDemo.class }, (proxy, method, arguments) -> {
                    Object[] request = { version, address, method.getName(), method.getParameterTypes(), arguments };
                    return exchange("client", request);
                });
    }

    static Object exchange(String mode, Object[] input) throws Exception {
        // 一次客户端或编码操作拥有一个独立进程；文件和日志留在构建目录便于定位。
        Path directory = Files.createTempDirectory(outputDirectory(), "operation-");
        Path request = directory.resolve("request.bin");
        Path response = directory.resolve("response.bin");
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(request))) {
            stream.writeObject(input);
        }
        List<String> arguments = List.of(mode, request.toString(), response.toString());
        try (CauchoHessianPeerProcess peer = start(arguments)) {
            peer.requireCompletion();
            Object[] result;
            try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(response))) {
                result = (Object[]) stream.readObject();
            }
            Throwable failure = (Throwable) result[1];
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure != null) {
                throw new AssertionError(failure);
            }
            return result[0];
        }
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    int getPort() {
        return this.port;
    }

    long processId() {
        return this.process.pid();
    }

    private static CauchoHessianPeerProcess start(List<String> arguments) throws IOException {
        String classpath = JavaSdkPeerUtils.classpath("hessian");
        Path log = Files.createTempFile(outputDirectory(), "peer-", ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add("net.hasor.peer.protocol.hessian.CauchoHessianPeer");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("java", builder);
        return new CauchoHessianPeerProcess(process, log);
    }

    private static Path outputDirectory() throws IOException {
        String configured = System.getProperty("hessian.peer.output.directory", "build/reports/hessian-peer");
        Path directory = Path.of(configured).toAbsolutePath();
        Files.createDirectories(directory);
        return directory;
    }

    private int awaitPort() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Official Caucho server startup timed out");
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Official Caucho server exited before listening");
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    private void requireCompletion() throws InterruptedException {
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw this.failure("Official Caucho operation timed out");
        }
        this.reader.join(1000);
        boolean completed = this.output.toString().contains("RESULT completed\n");
        if (this.process.exitValue() != 0 || !completed) {
            throw this.failure("Official Caucho operation did not complete");
        }
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; native log: " + this.log + "\n" + this.output);
    }

    private void readOutput() {
        // 持续排空原生对端日志，不能让子进程因输出缓冲区满而阻塞。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader stream = new BufferedReader(input)) {
            String line;
            while ((line = stream.readLine()) != null) {
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
        // 正常关闭通过 stdin EOF 通知服务端；失败时也回收本对象创建的进程。
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
