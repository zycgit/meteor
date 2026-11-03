/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.standard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.jupiter.api.Assertions.*;

/** 只拥有当前矩阵单元的一个进程，保留标准事件、日志及退出证据。 */
final class StandardPeerProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Process process;
    private final String endpoint;
    private final Path stdout;
    private final Path stderr;

    StandardPeerProcess(Process process, String endpoint, Path stdout, Path stderr) {
        this.process = process;
        this.endpoint = endpoint;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    static StandardPeerProcess start(JsonNode endpoint, Path directory, Path requestFile,
            JsonNode request, Map<String, String> variables) throws IOException {
        Files.createDirectories(directory);
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        List<String> command = new ArrayList<>();
        for (JsonNode argument : endpoint.required("command")) {
            command.add(expand(argument.textValue(), variables));
        }
        String workingDirectory = expand(endpoint.required("workingDirectory").textValue(), variables);
        ProcessBuilder builder = new ProcessBuilder(command) //
                .directory(Path.of(workingDirectory).toFile()) //
                .redirectOutput(stdout.toFile()) //
                .redirectError(stderr.toFile());
        endpoint.path("environment").fields().forEachRemaining(entry -> {
            builder.environment().put(entry.getKey(), expand(entry.getValue().textValue(), variables));
        });

        // 业务输入只来自已经固化的计划，注册发现消费者不会收到提供者地址。
        builder.environment().put("METEOR_NXN_REQUEST_FILE", requestFile.toString());
        if (request.has("id") && request.has("description") && request.has("attachment")) {
            builder.environment().put("METEOR_NXN_ORDER_ID", request.required("id").asText());
            builder.environment().put("METEOR_NXN_ORDER_DESCRIPTION", request.required("description").textValue());
            builder.environment().put("METEOR_NXN_ORDER_ATTACHMENT", request.required("attachment").textValue());
        }
        builder.environment().put("METEOR_ORDER_BIND_HOST", "0.0.0.0");
        builder.environment().put("METEOR_ORDER_PORT", "0");
        builder.environment().remove("METEOR_ORDER_TARGET");
        String target = variables.get("${target}");
        if (target != null) {
            builder.environment().put("METEOR_ORDER_TARGET", target);
        }

        // stdin 保持空闲；有些提供者用 EOF 表示关闭，生命周期由本例统一收尾。
        Process process = ContainerProcess.start(endpoint.required("container").textValue(), builder);
        return new StandardPeerProcess(process, endpoint.required("id").textValue(), stdout, stderr);
    }

    JsonNode awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            JsonNode ready = event("ready");
            if (ready != null) {
                assertRunning();
                return ready;
            }
            assertRunning();
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while waiting for provider: " + this.endpoint);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError(details("Provider startup timed out"));
    }

    JsonNode awaitResult() throws Exception {
        // 成功事件必须伴随正常退出；只打印结果后崩溃仍然是失败。
        assertTrue(this.process.waitFor(60, TimeUnit.SECONDS), () -> details("Consumer timed out"));
        assertEquals(0, this.process.exitValue(), () -> details("Consumer exited unsuccessfully"));
        JsonNode result = event("result");
        assertNotNull(result, () -> details("Consumer did not emit a result event"));
        return result;
    }

    void assertRunning() {
        assertTrue(this.process.isAlive(), () -> details("Provider exited before the scenario completed"));
    }

    private JsonNode event(String name) throws IOException {
        String output = Files.readString(this.stdout, StandardCharsets.UTF_8);
        JsonNode found = null;
        int start = 0;
        int end;
        while ((end = output.indexOf('\n', start)) >= 0) {
            String line = output.substring(start, end).trim();
            start = end + 1;
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode current = JSON.readTree(line);
            String event = current.path("event").asText();
            if (StringUtils.equals("error", event)) {
                throw new AssertionError(details("Peer emitted an error: " + current));
            }
            if (StringUtils.equals(name, event)) {
                assertNull(found, () -> details("Duplicate " + name + " event"));
                found = current;
            }
        }
        return found;
    }

    private String details(String message) {
        return message + ": " + this.endpoint + "; stdout: " + this.stdout + "; stderr: " + this.stderr;
    }

    private static String expand(String value, Map<String, String> variables) {
        if (value == null) {
            throw new IllegalArgumentException("Matrix launch values must be strings");
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        if (value.contains("${")) {
            throw new IllegalArgumentException("Unresolved matrix launch value: " + value);
        }
        return value;
    }

    @Override
    public void close() throws Exception {
        // 只关闭本对象创建的进程树，不管理借入的语言容器。
        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                assertTrue(this.process.waitFor(5, TimeUnit.SECONDS), () -> details("Peer did not terminate"));
            }
        }
    }
}
