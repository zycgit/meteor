/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.adapter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.example.Order;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 拥有独立官方 Hprose JVM；控制管道只交付发现结果和业务数据，不代理网络 RPC。 */
final class HproseJavaOrderPeerProcess implements AutoCloseable {
    private final Process         process;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final Path            log;
    private final int             port;

    private HproseJavaOrderPeerProcess(String role, Path directory) throws Exception {
        // Gradle 提供 SDK 示例的独立运行图与标准测试输出，不加载 Meteor 运行依赖。
        String classpath = System.getProperty("hprose.java.peer.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Official Hprose peer classpath is not configured");
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Files.createDirectories(directory);
        this.log = Files.createTempFile(directory, "hprose-java-" + role + "-", ".log");
        String main = "net.hasor.peer.protocol.hprose.HproseOrderPeer";
        ProcessBuilder command = new ProcessBuilder(java.toString(), "-cp", classpath, main, role);
        this.process = ContainerProcess.start("java", command.redirectError(this.log.toFile()));
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.input = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
        try {
            String ready = this.read();
            if (!ready.startsWith("READY ")) {
                throw new IOException("Official Hprose peer did not announce readiness: " + ready);
            }
            this.port = Integer.parseInt(ready.substring(6));
        } catch (Exception failure) {
            this.process.destroyForcibly();
            this.process.waitFor(5, TimeUnit.SECONDS);
            this.reader.shutdownNow();
            this.input.close();
            this.output.close();
            throw failure;
        }
    }

    static HproseJavaOrderPeerProcess consumer(Path directory) throws Exception {
        return new HproseJavaOrderPeerProcess("consumer", directory);
    }

    static HproseJavaOrderPeerProcess provider(Path directory) throws Exception {
        return new HproseJavaOrderPeerProcess("provider", directory);
    }

    InterAddress address(String unit) {
        return new InterAddress("http", ContainerProcess.host(this.process), this.port, unit);
    }

    long processId() {
        return this.process.pid();
    }

    Order[] roundTrip(URI discovered, Order order) throws Exception {
        // URL 在独立客户端完成启动后才由存储订阅结果提供，包括真实 scheme、端口和挂载路径。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) {
            stream.writeObject(new Object[] { discovered.toString(), order });
        }
        this.input.write(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        this.input.newLine();
        this.input.flush();

        // 这里只还原子进程的控制结果；save/find 的 Hprose 请求均由官方 SDK 发出。
        String response = this.read();
        if (!response.startsWith("RESULT ")) {
            throw new IOException("Official Hprose peer returned an invalid control result: " + response);
        }
        byte[] result = Base64.getDecoder().decode(response.substring(7));
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(result))) {
            return (Order[]) stream.readObject();
        }
    }

    private String read() throws Exception {
        String line = this.reader.submit(this.output::readLine).get(20, TimeUnit.SECONDS);
        if (line == null) {
            throw new IOException("Official Hprose peer closed its control pipe:\n" + Files.readString(this.log));
        }
        return line;
    }

    @Override
    public void close() throws Exception {
        try {
            // stdin EOF 通知独立进程停止自己的监听和 SDK 线程池。
            this.input.close();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
            this.reader.shutdownNow();
            this.output.close();
        }
    }
}
