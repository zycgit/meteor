/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.python;
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
import net.hasor.example.contract.types.TypeValue;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独占一个 Python SDK 容器，JSON 仅用于选择向量、报告结果和关闭进程。 */
final class PythonSoapTypePeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process         process;
    private final Path            log;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final int             port;

    private PythonSoapTypePeer(String role) throws Exception {
        // 复用已锁定 SDK 环境，类型场景不修改旧订单对端和契约。
        String logs = System.getProperty("python.soap.logs", "build/python-soap-peer-logs");
        Path directory = Files.createDirectories(Path.of(logs));
        this.log = Files.createTempFile(directory, "types-" + role + "-", ".log");
        String executable = System.getProperty("python.soap.executable", "/opt/meteor-peer/python-soap/bin/python");
        Path peers = Path.of(System.getProperty("meteor.test.peerDirectory", "peer"));
        String orderScript = System.getProperty("python.soap.script", peers.resolve("python/soap/peer.py").toString());
        Path script = Path.of(orderScript).resolveSibling("type_peer.py");
        ProcessBuilder command = new ProcessBuilder(executable, "-u", script.toString(), role);
        command.redirectError(this.log.toFile());
        this.process = ContainerProcess.start("python", command);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.input = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();

        try {
            JsonNode ready = this.read();
            if (!ready.path("ready").asBoolean()) {
                throw new IOException("Python SOAP types did not become ready: " + ready);
            }
            this.port = ready.path("port").asInt();
        } catch (Exception failure) {
            try {
                this.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static PythonSoapTypePeer provider() throws Exception {
        return new PythonSoapTypePeer("provider");
    }

    static PythonSoapTypePeer consumer() throws Exception {
        return new PythonSoapTypePeer("consumer");
    }

    InterAddress address(String unit) {
        return new InterAddress("http", ContainerProcess.host(this.process), this.port, unit);
    }

    TypeValue echo(URI wsdl, String vector) throws Exception {
        // Zeep 从公开 WSDL 获取定义，再以自己恢复的原生 Python 值发起调用。
        ObjectNode command = JSON.createObjectNode();
        command.put("operation", "echo");
        command.put("wsdl", wsdl.toASCIIString());
        command.put("vector", vector);
        this.write(command);
        JsonNode response = this.read();
        if (response.has("error")) {
            throw new IOException("Python SOAP type call failed: " + response.path("error").asText());
        }
        return JSON.treeToValue(response.required("result"), TypeValue.class);
    }

    private JsonNode read() throws Exception {
        String line = this.reader.submit(this.output::readLine).get(20, TimeUnit.SECONDS);
        if (line == null) {
            throw new IOException("Python SOAP types closed its control pipe; log: " + this.log);
        }
        return JSON.readTree(line);
    }

    private void write(JsonNode command) throws IOException {
        this.input.write(JSON.writeValueAsString(command));
        this.input.newLine();
        this.input.flush();
    }

    @Override
    public void close() throws Exception {
        try {
            // 先请求 SDK 关闭监听，再检查实际容器进程的退出状态。
            if (this.process.isAlive()) {
                ObjectNode command = JSON.createObjectNode();
                command.put("operation", "quit");
                this.write(command);
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Python SOAP types did not stop; log: " + this.log);
                }
            }
            if (this.process.exitValue() != 0) {
                throw new IOException("Python SOAP types failed; log: " + this.log);
            }
        } finally {
            // 创建者回收自己持有的进程、控制管道和读取线程。
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
