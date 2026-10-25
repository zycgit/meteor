/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.python;
import java.io.BufferedReader;
import java.io.BufferedWriter;
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

/** 只管理当前 Python 类型对端容器和输出，不持有 Meteor 上下文或协议编解码。 */
public final class PythonTypePeerProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process               process;
    private final Path                  log;
    private final Thread                reader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private PythonTypePeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "python-type-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static PythonTypePeerProcess provider(String protocol, String encoding, String vector) throws Exception {
        return start("provider", protocol, encoding, vector, null, 0);
    }

    public static PythonTypePeerProcess consumer(String protocol, String encoding, String vector, String host, int port) //
            throws Exception {
        return start("consumer", protocol, encoding, vector, host, port);
    }

    public int awaitPort() throws Exception {
        return Integer.parseInt(this.awaitLine("READY "));
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public void awaitSuccess(String vector) throws Exception {
        // 类型断言由独立 Python SDK 完成；同时校验场景身份和进程退出状态。
        JsonNode result = JSON.readTree(this.awaitLine("RESULT "));
        if (!result.path("ok").asBoolean() || !StringUtils.equals(vector, result.path("vector").asText())) {
            throw this.failure("Unexpected Python type result: " + result);
        }
        if (!this.process.waitFor(5, TimeUnit.SECONDS) || this.process.exitValue() != 0) {
            throw this.failure("Python type consumer did not exit successfully");
        }
    }

    private static PythonTypePeerProcess start(String role, String protocol, String encoding, String vector, //
            String host, int port) throws Exception {
        // 复用已锁定的 Python SDK 镜像，契约由 Docker 内的官方生成器准备。
        boolean grpc = StringUtils.equals(protocol, "grpc");
        String runtime = grpc ? "grpc" : "thrift";
        Path existingScript = Path.of(System.getProperty("python." + runtime + ".script"));
        Path scripts = existingScript.getParent().getParent().resolve("types");
        Path vectors = Path.of(PythonTypePeerProcess.class.getResource("/vectors/types/values.json").toURI());
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("python." + runtime + ".executable"));
        command.add(scripts.resolve(runtime + "_peer.py").toString());
        command.add(role);
        command.add("--generated");
        command.add(grpc ? "/opt/meteor-peer/python-types-grpc-generated" : scripts.resolve("thriftgen").toString());
        command.add("--vectors");
        command.add(vectors.toString());
        command.add("--vector");
        command.add(vector);
        command.add("--encoding");
        command.add(encoding);
        if (host != null) {
            command.add("--host");
            command.add(host);
            command.add("--port");
            command.add(Integer.toString(port));
        }

        // 每个参数实例独享日志和容器，现有订单 peer 的契约、参数和行为保持独立。
        Path directory = Path.of(System.getProperty("python." + runtime + ".logs")).resolve("types");
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, role + "-" + vector + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("python", builder);
        return new PythonTypePeerProcess(process, log);
    }

    private String awaitLine(String prefix) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Python type peer timed out waiting for " + prefix);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Python type peer exited before " + prefix);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; peer log: " + this.log);
    }

    private void readOutput() {
        // 持续排空 stdout 并保存完整日志，测试只读取就绪与成功通知。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input); //
             BufferedWriter output = Files.newBufferedWriter(this.log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write(line);
                output.newLine();
                output.flush();
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.lines.offer("ERROR " + failure);
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者发出 EOF 停止自己的 Python 服务，再回收容器；不关闭任何借入对象。
        try {
            this.process.getOutputStream().close();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw this.failure("Python type peer did not close");
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw this.failure("Python type peer failed");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
