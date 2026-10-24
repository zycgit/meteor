/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.python;
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

/** 拥有 Python 标准库 JSON-RPC 对端进程，不是官方 SDK，也不代理业务通信。 */
final class PythonJsonRpcOrderPeer implements AutoCloseable {
    private final Process         process;
    private final BufferedReader  output;
    private final ExecutorService reader;
    private final Path            log;

    private PythonJsonRpcOrderPeer(List<String> arguments) throws IOException {
        // 错误日志与控制通知分离，业务网络请求全部在独立 Python 进程中执行。
        Path script = Path.of("src/test/python/jsonrpc/orders/order_jsonrpc_peer.py").toAbsolutePath();
        List<String> command = new ArrayList<>(List.of("python3", "-u", script.toString()));
        command.addAll(arguments);
        Path logs = Files.createDirectories(Path.of("build/peer-logs/python-jsonrpc-orders"));
        this.log = Files.createTempFile(logs, "peer-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(this.log.toFile());
        this.process = ContainerProcess.start("python", builder);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
    }

    static PythonJsonRpcOrderPeer provider() throws IOException {
        return new PythonJsonRpcOrderPeer(List.of("provider"));
    }

    static PythonJsonRpcOrderPeer consumer(String endpoint, String scenario) throws IOException {
        Path vectors = Path.of("src/test/resources/vectors/basic/orders.json").toAbsolutePath();
        List<String> arguments = List.of("consumer", "--endpoint", endpoint, //
                "--scenario", scenario, "--vectors", vectors.toString());
        return new PythonJsonRpcOrderPeer(arguments);
    }

    int awaitPort() throws Exception {
        // 端口只取独立服务端监听后的通知，启动失败有明确等待上限。
        String line = this.reader.submit(this.output::readLine).get(15, TimeUnit.SECONDS);
        if (line == null || !line.startsWith("READY ")) {
            throw new IOException("Python JSON-RPC listener failed: " + line + "\n" + Files.readString(this.log));
        }
        return Integer.parseInt(line.substring(6));
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    long processId() {
        return this.process.pid();
    }

    void requireSuccess() throws Exception {
        // 消费者必须完成自身断言并正常退出，进程退出不是业务成功的替代证据。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new IOException("Python JSON-RPC consumer timed out:\n" + Files.readString(this.log));
        }
        String marker = this.reader.submit(this.output::readLine).get(5, TimeUnit.SECONDS);
        if (this.process.exitValue() != 0 || !StringUtils.equals("OK", marker)) {
            throw new AssertionError("Python JSON-RPC consumer failed:\n" + Files.readString(this.log));
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者关闭控制输入；服务端先停止监听，超时再终止进程并释放读线程。
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
