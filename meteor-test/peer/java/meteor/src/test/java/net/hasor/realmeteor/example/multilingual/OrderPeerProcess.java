/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.Assert.*;

/** 持有常驻语言容器中的独立订单进程、日志和有界等待。 */
final class OrderPeerProcess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final        Process      process;
    private final        Path         log;

    private OrderPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static List<Object[]> languagesAndProtocols() {
        List<Object[]> peers = new ArrayList<>();
        peers.add(new Object[] { "python", "grpc" });
        peers.add(new Object[] { "python", "thrift" });
        peers.add(new Object[] { "python", "soap" });
        peers.add(new Object[] { "python", "jsonrpc" });
        peers.add(new Object[] { "python", "xmlrpc" });
        peers.add(new Object[] { "node", "hprose" });
        peers.add(new Object[] { "node", "jsonrpc" });
        peers.add(new Object[] { "node", "connect" });
        peers.add(new Object[] { "go", "grpc" });
        peers.add(new Object[] { "go", "connect" });
        peers.add(new Object[] { "go", "thrift" });
        peers.add(new Object[] { "c", "oncrpc" });
        peers.add(new Object[] { "cpp", "oncrpc" });
        return peers;
    }

    static OrderPeerProcess javaMain(String protocol, String role, String target) throws Exception {
        // 应用入口和运行依赖均归测试工程，只挂载当前协议。
        String classpath = System.getProperty("meteor.test.application.classpath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("meteor.test.application.classpath is required");
        }
        String main = "net.hasor.peer.application.OrderApplicationMain";
        List<String> command = List.of("java", "-cp", classpath, main, protocol, role);
        String runtime = "java-meteor";
        if (StringUtils.equals("Provider", role)) {
            runtime = "java-meteor-peer";
        }
        return start(runtime, command, target);
    }

    static OrderPeerProcess foreignProvider(String language, String protocol) throws Exception {
        List<String> command = foreignCommand(language, protocol, "provider");
        return start(runtime(language), command, null);
    }

    static OrderPeerProcess foreignConsumer(String language, String protocol, String target) throws Exception {
        // 测试自有消费者直接执行保存与查询，不从外部示例工程取入口。
        List<String> command = foreignCommand(language, protocol, "consumer");
        return start(runtime(language), command, target);
    }

    static OrderPeerProcess start(String runtime, List<String> command, String target) throws Exception {
        // 各语言复用自己的常驻容器，每个场景独占进程和端口。
        Path log = Files.createTempFile("meteor-language-order-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("METEOR_ORDER_BIND_HOST", "0.0.0.0");
        builder.environment().put("METEOR_ORDER_PORT", "0");
        if (target != null) {
            builder.environment().put("METEOR_ORDER_TARGET", target);
        }
        try {
            return new OrderPeerProcess(ContainerProcess.start(runtime, builder), log);
        } catch (Exception failure) {
            Files.deleteIfExists(log);
            throw failure;
        }
    }

    private static List<String> foreignCommand(String language, String protocol, String role) {
        List<String> command = new ArrayList<>();
        if (StringUtils.equals("python", language)) {
            String executable = "python3";
            boolean sdk = List.of("grpc", "thrift", "soap").contains(protocol);
            if (sdk) {
                executable = "/opt/meteor-peer/python-" + protocol + "/bin/python";
            }
            Path source = sources().resolve("python").resolve(protocol).resolve(role + ".py");
            command.addAll(List.of(executable, source.toString()));
        } else if (StringUtils.equals("node", language)) {
            command.addAll(List.of("node", "/opt/meteor-peer/node/" + protocol + "/" + role + ".mjs"));
        } else if (StringUtils.equals("go", language)) {
            command.add("/opt/meteor-peer/go/order/" + protocol + "-" + role);
        } else {
            command.add("/opt/meteor-peer/native/order/" + language + "/" + role);
        }
        return command;
    }

    private static String runtime(String language) {
        if (StringUtils.equals("c", language) || StringUtils.equals("cpp", language)) {
            return "native";
        }
        return language;
    }

    static Path sources() {
        return Path.of(System.getProperty("meteor.test.peerDirectory")).toAbsolutePath();
    }

    String awaitReady(String prefix) throws Exception {
        // 等待当前对端完成启动；应用提前退出时立即保留日志报告。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            String output = this.output();
            for (String line : output.split("\\R")) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
            assertTrue("Provider exited before listening:\n" + output, this.process.isAlive());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider startup timed out:\n" + this.output());
    }

    JsonNode awaitResult() throws Exception {
        String output = this.awaitOutput();
        List<String> results = output.lines().filter(line -> line.startsWith("{")).toList();
        assertFalse("Order consumer produced no JSON order:\n" + output, results.isEmpty());
        return JSON.readTree(results.get(results.size() - 1));
    }

    String awaitOutput() throws Exception {
        // 消费者完成调用后正常退出，原始输出由当前场景按协议解释。
        boolean finished = this.process.waitFor(45, TimeUnit.SECONDS);
        String output = this.output();
        assertTrue("Order consumer timed out:\n" + output, finished);
        assertEquals("Order consumer failed:\n" + output, 0, this.process.exitValue());
        return output;
    }

    private String output() throws Exception {
        return Files.readString(this.log, StandardCharsets.UTF_8);
    }

    static void assertOrder(JsonNode result, String description, String attachment) {
        // 各语言保留自己的常规输出包装，断言最终查询到的订单。
        JsonNode order = result;
        if (result.has("result")) {
            order = result.required("result");
        } else if (result.has("found")) {
            order = result.required("found");
        }
        assertEquals(1001, order.required("id").intValue());
        assertEquals(description, order.required("description").textValue());

        // C/C++ 输出字节数组，其余对端输出 Base64，二者均验证原始附件内容。
        JsonNode actualAttachment = order.required("attachment");
        if (actualAttachment.isArray()) {
            byte[] expected = Base64.getDecoder().decode(attachment);
            assertEquals(expected.length, actualAttachment.size());
            for (int index = 0; index < expected.length; index++) {
                assertEquals(expected[index] & 0xff, actualAttachment.get(index).intValue());
            }
        } else {
            assertEquals(attachment, actualAttachment.textValue());
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者只结束自己的对端进程，常驻语言容器由外层编排管理。
        try {
            if (this.process.isAlive()) {
                this.process.destroy();
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    this.process.destroyForcibly();
                    assertTrue("Order peer did not exit", this.process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            Files.deleteIfExists(this.log);
        }
    }
}
