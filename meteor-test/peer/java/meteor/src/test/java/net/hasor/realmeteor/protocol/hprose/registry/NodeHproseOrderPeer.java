/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.registry;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 拥有官方 Node Hprose 对端进程；控制管道不承载任何 Java 代发的业务 RPC。 */
final class NodeHproseOrderPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process         process;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final Path            log;
    private final int             port;

    private NodeHproseOrderPeer(String role, Path workDirectory) throws Exception {
        // 消费者仅收到角色和共享向量路径，业务端点之后由存储订阅结果提供。
        Path directory = Path.of(System.getProperty("hprose.peer.dir", "/opt/meteor-peer/node/hprose"));
        Path script = directory.resolve("order-registry-peer.js").toAbsolutePath();
        URI vectors = NodeHproseOrderPeer.class.getResource("/data/basic/orders.json").toURI();
        Files.createDirectories(workDirectory);
        this.log = Files.createTempFile(workDirectory, "node-hprose-" + role + "-", ".log");
        ProcessBuilder command = new ProcessBuilder("node", script.toString(), role, Path.of(vectors).toString());
        command.redirectError(this.log.toFile());
        this.process = ContainerProcess.start("node", command);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.input = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
        try {
            JsonNode ready = this.read();
            if (!ready.path("ready").asBoolean()) {
                throw new IOException("Node Hprose peer did not announce readiness: " + ready);
            }
            this.port = ready.path("port").asInt();
        } catch (Exception failure) {
            this.process.destroyForcibly();
            this.process.waitFor(5, TimeUnit.SECONDS);
            this.reader.shutdownNow();
            this.input.close();
            this.output.close();
            throw failure;
        }
    }

    static NodeHproseOrderPeer consumer(Path workDirectory) throws Exception {
        return new NodeHproseOrderPeer("consumer", workDirectory);
    }

    static NodeHproseOrderPeer provider(Path workDirectory) throws Exception {
        return new NodeHproseOrderPeer("provider", workDirectory);
    }

    InterAddress address(String unit) {
        return new InterAddress("http", ContainerProcess.host(this.process), this.port, unit);
    }

    long processId() {
        return this.process.pid();
    }

    JsonNode roundTrip(URI discovered, String scenario) throws Exception {
        // 真实 URL 包含订阅所得主机、端口和 context path；Java 只发送控制命令。
        ObjectNode command = JSON.createObjectNode();
        command.put("address", discovered.toString());
        command.put("scenario", scenario);
        this.write(command);
        JsonNode result = this.read();
        if (result.has("error")) {
            throw new IOException("Node Hprose business call failed: " + result.get("error").asText());
        }
        return result.required("result");
    }

    private void write(JsonNode command) throws IOException {
        this.input.write(JSON.writeValueAsString(command));
        this.input.newLine();
        this.input.flush();
    }

    private JsonNode read() throws Exception {
        String line = this.reader.submit(this.output::readLine).get(20, TimeUnit.SECONDS);
        if (line == null) {
            throw new IOException("Node Hprose closed its control pipe:\n" + Files.readString(this.log));
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
            // 先结束拥有的进程使阻塞读取返回，再释放控制管道和读取线程。
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
