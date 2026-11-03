/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独占 Python 标准库类型对端；控制管道仅传端口和完成信号。 */
final class XmlRpcTypePeerProcess implements AutoCloseable {
    private final Process         process;
    private final BufferedReader  output;
    private final ExecutorService reader;
    private final Path            log;

    private XmlRpcTypePeerProcess(List<String> arguments) throws IOException {
        // 脚本与原 XML-RPC 对端同目录，沿用已有运行属性和 Python 容器。
        Path peers = Path.of(System.getProperty("meteor.test.peerDirectory", "peer"));
        Path defaultScript = peers.resolve("python/xmlrpc/peer.py");
        Path configuredScript = Path.of(System.getProperty("xmlrpc.peer.script", defaultScript.toString()));
        Path script = configuredScript.resolveSibling("type_peer.py");
        String python = System.getProperty("xmlrpc.python", "python3");
        List<String> command = new ArrayList<>(List.of(python, "-u", script.toString()));
        command.addAll(arguments);
        Path directory = Files.createDirectories(Path.of("build/peer-logs/python-xmlrpc-types"));
        this.log = Files.createTempFile(directory, "peer-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(this.log.toFile());
        this.process = ContainerProcess.start("python", builder);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
    }

    static XmlRpcTypePeerProcess provider(String vector) throws IOException {
        return new XmlRpcTypePeerProcess(List.of("provider", vector));
    }

    static XmlRpcTypePeerProcess consumer(String target, String vector) throws IOException {
        return new XmlRpcTypePeerProcess(List.of("consumer", vector, target));
    }

    static XmlRpcTypePeerProcess rejectedConsumer(String target, String vector) throws IOException {
        return new XmlRpcTypePeerProcess(List.of("rejected-consumer", vector, target));
    }

    int awaitPort() throws Exception {
        // 真实服务端监听后才公布端口；启动失败保留标准库的错误日志。
        String line = this.reader.submit(this.output::readLine).get(15, TimeUnit.SECONDS);
        if (line == null || !line.startsWith("READY ")) {
            throw new IOException("Python XML-RPC type provider failed: " + line + "\n" + Files.readString(this.log));
        }
        return Integer.parseInt(line.substring(6));
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    void requireSuccess() throws Exception {
        this.requireCompletion("OK");
    }

    void requireRangeRejection() throws Exception {
        this.requireCompletion("REJECTED:int32");
    }

    void requireNoInvocation() throws Exception {
        // 控制查询只确认业务未进入，与服务类型向量的真实 RPC 通道分离。
        this.process.getOutputStream().write("CHECK\n".getBytes(StandardCharsets.UTF_8));
        this.process.getOutputStream().flush();
        String marker = this.reader.submit(this.output::readLine).get(5, TimeUnit.SECONDS);
        if (!StringUtils.equals("NOT_INVOKED", marker)) {
            throw new AssertionError("Python XML-RPC provider unexpectedly invoked: " + marker);
        }
    }

    private void requireCompletion(String expectedMarker) throws Exception {
        // 对端必须完成原生类型和业务收据断言，正常退出本身不足以证明互通。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new IOException("Python XML-RPC type consumer timed out:\n" + Files.readString(this.log));
        }
        String marker = this.reader.submit(this.output::readLine).get(5, TimeUnit.SECONDS);
        if (this.process.exitValue() != 0 || !StringUtils.equals(expectedMarker, marker)) {
            throw new AssertionError("Python XML-RPC type consumer failed:\n" + Files.readString(this.log));
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者关闭 stdin 通知监听退出，再终止未及时退出的独占进程。
        try {
            this.process.getOutputStream().close();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
        } finally {
            this.reader.shutdownNow();
            this.output.close();
        }
    }
}
