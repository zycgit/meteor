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
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独占真实 Zeep 或 soapfish2 进程；标准输入仅传控制数据，不代发 SOAP 业务调用。 */
final class PythonSoapPeer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process         process;
    private final Path            log;
    private final BufferedReader  output;
    private final BufferedWriter  input;
    private final ExecutorService reader;
    private final int             port;

    private PythonSoapPeer(String role) throws Exception {
        // 对端只加载锁定的 Python SDK；日志与 JSON 控制响应使用不同管道。
        Path directory = Files.createDirectories(Path.of(System.getProperty("python.soap.logs")));
        this.log = Files.createTempFile(directory, role + "-", ".log");
        String executable = "/opt/meteor-peer/python-soap/bin/python";
        String script = System.getProperty("python.soap.script");
        ProcessBuilder command = new ProcessBuilder(executable, "-u", script, role);
        command.redirectError(this.log.toFile());
        this.process = ContainerProcess.start("python", command);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.input = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();

        try {
            JsonNode ready = this.read();
            if (!ready.path("ready").asBoolean()) {
                throw new IOException("Python SOAP SDK did not announce readiness: " + ready);
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

    static PythonSoapPeer provider() throws Exception {
        return new PythonSoapPeer("provider");
    }

    static PythonSoapPeer consumer() throws Exception {
        return new PythonSoapPeer("consumer");
    }

    InterAddress address(String unit) {
        return new InterAddress("http", this.host(), this.port, unit);
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    long processId() {
        return this.process.pid();
    }

    JsonNode roundTrip(URI wsdl, JsonNode order) throws Exception {
        // Zeep 自己读取在线 WSDL、编码 save 参数、解码 find 响应。
        ObjectNode command = JSON.createObjectNode();
        command.put("operation", "roundtrip");
        command.put("wsdl", wsdl.toASCIIString());
        command.set("order", order);
        this.write(command);
        JsonNode response = this.read();
        if (response.has("error")) {
            throw new IOException("Python SOAP SDK call failed: " + response.path("error").asText());
        }
        return response.get("result");
    }

    private JsonNode read() throws Exception {
        String line = this.reader.submit(this.output::readLine).get(20, TimeUnit.SECONDS);
        if (line == null) {
            throw new IOException("Python SOAP peer closed its control pipe; log: " + this.log);
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
            if (this.process.isAlive()) {
                ObjectNode command = JSON.createObjectNode();
                command.put("operation", "quit");
                this.write(command);
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Python SOAP peer did not stop; log: " + this.log);
                }
            }
            if (this.process.exitValue() != 0) {
                throw new IOException("Python SOAP peer failed; log: " + this.log);
            }
        } finally {
            // 创建者结束进程以释放读取，再关闭自己持有的控制管道和线程。
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
