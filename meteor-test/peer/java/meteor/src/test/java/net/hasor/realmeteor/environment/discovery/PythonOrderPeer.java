/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.discovery;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 拥有独立 Python 进程与控制管道；业务调用完全由 Python XML-RPC SDK 收发。 */
public final class PythonOrderPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process         process;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final Path            log;
    private final int             port;

    private PythonOrderPeer(String role, int excludedPort) throws Exception {
        // 日志与控制结果分开，Python 消费者的启动参数中没有任何业务端点。
        Path script = Path.of("peer/python/orders/order_peer.py").toAbsolutePath();
        Path logs = Files.createDirectories(Path.of("build/peer-logs/registry-adapter"));
        this.log = Files.createTempFile(logs, role + "-", ".log");
        ProcessBuilder command = new ProcessBuilder("python3", "-u", script.toString(), role, //
                "--exclude-port", Integer.toString(excludedPort));
        command.redirectError(this.log.toFile());
        this.process = ContainerProcess.start("python", command);
        this.input = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
        JsonNode ready;
        try {
            ready = this.read();
            if (!ready.path("ready").asBoolean()) {
                throw new IOException("Python did not announce readiness: " + ready);
            }
        } catch (Exception failure) {
            this.process.destroyForcibly();
            this.process.waitFor(5, TimeUnit.SECONDS);
            this.reader.shutdownNow();
            this.input.close();
            this.output.close();
            throw failure;
        }
        this.port = ready.path("port").asInt();
    }

    public static PythonOrderPeer consumer() throws Exception {
        return new PythonOrderPeer("consumer", 0);
    }

    public static PythonOrderPeer provider(int excludedPort) throws Exception {
        return new PythonOrderPeer("provider", excludedPort);
    }

    public InterAddress address(String unit) {
        return new InterAddress("xmlrpc", this.host(), this.port, unit);
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public long processId() {
        return this.process.pid();
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    public JsonNode roundTrip(List<URI> discovered, JsonNode vector) throws Exception {
        // 端点来自真实 StorageAccess resolver；Java 仅传控制数据，不执行任何业务 HTTP 请求。
        ObjectNode command = JSON.createObjectNode();
        command.put("operation", "roundtrip");
        command.set("endpoints", JSON.valueToTree(discovered));
        command.set("order", vector);
        this.write(command);
        JsonNode response = this.read();
        if (response.has("error")) {
            throw new IOException("Python business call failed: " + response.get("error"));
        }
        return response.get("result");
    }

    private void write(JsonNode command) throws IOException {
        this.input.write(JSON.writeValueAsString(command));
        this.input.newLine();
        this.input.flush();
    }

    private JsonNode read() throws Exception {
        String line = this.reader.submit(this.output::readLine).get(20, TimeUnit.SECONDS);
        if (line == null) {
            throw new IOException("Python closed its control pipe:\n" + Files.readString(this.log));
        }
        return JSON.readTree(line);
    }

    @Override
    public void close() throws Exception {
        try {
            if (this.process.isAlive()) {
                ObjectNode command = JSON.createObjectNode();
                command.put("operation", "quit");
                this.write(command);
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    this.process.destroyForcibly();
                    this.process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            // 创建者先结束进程使读取返回，再关闭控制管道和读线程。
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
            this.reader.shutdownNow();
            this.input.close();
            this.output.close();
        }
    }
}
