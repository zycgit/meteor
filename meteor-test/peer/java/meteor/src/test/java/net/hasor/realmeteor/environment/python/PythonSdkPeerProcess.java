/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.environment.python;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import static org.junit.Assert.*;

/** 只管理当前 Python SDK 进程；编解码及监听由独立 SDK 提供。 */
public final class PythonSdkPeerProcess implements AutoCloseable {
    private final Process process;
    private final Path log;

    private PythonSdkPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    public static PythonSdkPeerProcess start(String protocol, String... arguments) throws Exception {
        // SDK 由语言镜像锁定安装，场景只在共享容器内创建自己的业务进程。
        Path sources = Path.of(System.getProperty("meteor.test.peerDirectory"));
        Path script = sources.resolve("python").resolve(protocol).resolve("peer.py");
        String executable = "/opt/meteor-peer/python-" + protocol + "/bin/python";
        List<String> command = new ArrayList<>(List.of(executable, "-u", script.toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("python-" + protocol + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        return new PythonSdkPeerProcess(ContainerProcess.start("python", builder), log);
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public int awaitPort() throws Exception {
        // 等待真实监听的就绪标记；进程提前失败时立即展示 SDK 原始错误。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String output = this.output();
            for (String line : output.split("\\R")) {
                if (line.startsWith("READY ")) {
                    return Integer.parseInt(line.substring(6));
                }
            }
            assertTrue("Python SDK exited before listening:\n" + output, this.process.isAlive());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Python SDK startup timed out:\n" + this.output());
    }

    public void requireSuccess() throws Exception {
        // 退出码和完成标记共同证明当前 SDK 已完成本次真实调用及断言。
        boolean finished = this.process.waitFor(20, TimeUnit.SECONDS);
        String output = this.output();
        assertTrue("Python SDK timed out:\n" + output, finished);
        assertEquals("Python SDK failed:\n" + output, 0, this.process.exitValue());
        boolean success = output.lines().anyMatch(line -> StringUtils.equals("OK", line));
        assertTrue("Python SDK produced no success marker:\n" + output, success);
    }

    private String output() throws Exception {
        return Files.readString(this.log, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        // EOF 请求服务端停止监听；异常时仍回收本场景进程，不关闭共享语言容器。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
            boolean finished = this.process.waitFor(5, TimeUnit.SECONDS);
            assertTrue("Python SDK did not stop:\n" + this.output(), finished);
            assertEquals("Python SDK failed:\n" + this.output(), 0, this.process.exitValue());
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
