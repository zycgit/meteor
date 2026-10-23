/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect.registry;
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

/** 独占官方 Node Connect 对端及控制管道；地址发现适配与业务网络调用分别执行。 */
final class ConnectOrderRegistryPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process         process;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final Path            log;
    private final int             port;

    private ConnectOrderRegistryPeer(String role) throws Exception {
        // consumer 只接收角色参数，业务端点必须来自启动后的真实存储订阅。
        Path directory = Path.of(System.getProperty("connect.peer.dir"));
        Path logs = Files.createDirectories(Path.of("build/peer-logs/connect-registry-adapter"));
        this.log = Files.createTempFile(logs, role + "-", ".log");
        ProcessBuilder command = new ProcessBuilder("node", "order-registry-peer.mjs", role);
        command.directory(directory.toFile());
        command.redirectError(this.log.toFile());
        this.process = command.start();

        // 标准输出只承载 JSON 控制消息，错误日志由进程所有者保留用于失败诊断。
        InputStreamReader output = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        OutputStreamWriter input = new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8);
        this.output = new BufferedReader(output);
        this.input = new BufferedWriter(input);
        this.reader = Executors.newSingleThreadExecutor();
        JsonNode ready;
        try {
            ready = this.read();
            if (!ready.path("ready").asBoolean()) {
                throw new IOException("Connect Node peer did not announce readiness: " + ready);
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

    static ConnectOrderRegistryPeer consumer() throws Exception {
        return new ConnectOrderRegistryPeer("consumer");
    }

    static ConnectOrderRegistryPeer provider() throws Exception {
        return new ConnectOrderRegistryPeer("provider");
    }

    InterAddress address(String unit) {
        return new InterAddress("http", "127.0.0.1", this.port, unit);
    }

    long processId() {
        return this.process.pid();
    }

    boolean isAlive() {
        return this.process.isAlive();
    }

    JsonNode roundTrip(List<URI> discovered, JsonNode order) throws Exception {
        // resolver 给出的端点只作为控制数据；save/find 由官方 Node SDK 直接发送。
        ObjectNode command = JSON.createObjectNode();
        command.put("operation", "roundtrip");
        command.set("endpoints", JSON.valueToTree(discovered));
        command.set("order", order);
        this.write(command);
        JsonNode response = this.read();
        if (response.has("error")) {
            throw new IOException("Connect Node business call failed: " + response.get("error"));
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
            throw new IOException("Connect Node closed its control pipe:\n" + Files.readString(this.log));
        }
        return JSON.readTree(line);
    }

    @Override
    public void close() throws Exception {
        try {
            // 先通知外部所有者关闭 SDK 服务和通道，超时才回收进程。
            if (this.process.isAlive()) {
                ObjectNode command = JSON.createObjectNode();
                command.put("operation", "quit");
                this.write(command);
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    this.process.destroyForcibly();
                    this.process.waitFor(5, TimeUnit.SECONDS);
                    throw new IOException("Connect Node shutdown timed out:\n" + Files.readString(this.log));
                }
            }
            if (this.process.exitValue() != 0) {
                throw new IOException("Connect Node failed:\n" + Files.readString(this.log));
            }
        } finally {
            // 进程结束使阻塞读取返回后，再释放控制流和当前所有者创建的读线程。
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
