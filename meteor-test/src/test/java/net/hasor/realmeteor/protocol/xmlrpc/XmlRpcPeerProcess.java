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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Python 标准库对端；创建者负责有界启动、场景等待和进程回收。 */
final class XmlRpcPeerProcess implements AutoCloseable {
    private final Process               process;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();

    private XmlRpcPeerProcess(Process process) {
        this.process = process;
        this.reader = new Thread(this::readOutput, "xmlrpc-python-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static XmlRpcPeerProcess start(String... arguments) throws IOException {
        // 脚本属于当前插件测试资源；Python 只使用标准库，无安装步骤。
        Path script = Path.of(System.getProperty("xmlrpc.peer.script"));
        String python = System.getProperty("xmlrpc.python", "python3");
        List<String> command = new ArrayList<>(List.of(python, "-u", script.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new XmlRpcPeerProcess(process);
    }

    int awaitPort() throws InterruptedException {
        // 独立读取线程避免启动等待卡在 readLine；错误输出保留为失败证据。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("XML-RPC peer startup timed out:\n" + this.output);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw new AssertionError("XML-RPC peer stopped before listening:\n" + this.output);
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    void requireSuccess() throws InterruptedException {
        // 成功同时要求正常退出和场景完成标记，超时不计为互通成功。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new AssertionError("XML-RPC peer scenario timed out:\n" + this.output);
        }
        this.reader.join(1000);
        if (this.process.exitValue() != 0 || !this.output.toString().contains("OK\n")) {
            throw new AssertionError("XML-RPC peer scenario failed:\n" + this.output);
        }
    }

    private void readOutput() {
        // 持续排空子进程输出，结束标记使启动等待能立即感知异常退出。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.output.append(failure).append('\n');
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 先通过 stdin 请求正常退出；失败或超时仍终止并等待子进程回收。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
        } finally {
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
