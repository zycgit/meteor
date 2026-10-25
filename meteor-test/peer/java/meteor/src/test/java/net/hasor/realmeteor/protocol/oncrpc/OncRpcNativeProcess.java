/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独立 rpcgen/libtirpc C 对端；记录真实可执行文件身份并保留每次互通输出。 */
public final class OncRpcNativeProcess implements AutoCloseable {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final Object                USAGE_LOCK = new Object();
    private final        Process               process;
    private final        Thread                reader;
    private final        Path                  log;
    private final        BlockingQueue<String> lines      = new LinkedBlockingQueue<>();
    private final        StringBuffer          output     = new StringBuffer();

    private OncRpcNativeProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "oncrpc-native-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static OncRpcNativeProcess start(String... arguments) throws IOException {
        return start("oncrpc.peer.executable", arguments, false);
    }

    public static OncRpcNativeProcess startRpcbind(String... arguments) throws IOException {
        return start("oncrpc.rpcbind.peer.executable", arguments, false);
    }

    /** 本机 rpcbind 控制使用独立 C 容器进程，与调用方共享网络命名空间。 */
    public static OncRpcNativeProcess startLocalRpcbind(String... arguments) throws IOException {
        return start("oncrpc.rpcbind.peer.executable", arguments, true);
    }

    private static OncRpcNativeProcess start(String executableProperty, String[] arguments, boolean localControl) throws IOException {
        // 原生二进制由目标架构镜像提供，JUnit 和宿主均无需 libtirpc 或 C 工具链。
        String defaultExecutable;
        if (StringUtils.equals(executableProperty, "oncrpc.rpcbind.peer.executable")) {
            defaultExecutable = "/opt/meteor-peer/native/oncrpc_rpcbind_peer";
        } else {
            defaultExecutable = "/opt/meteor-peer/native/oncrpc_peer";
        }
        String executable = System.getProperty(executableProperty, defaultExecutable);
        String configuredOutput = System.getProperty("oncrpc.peer.output.directory", "build/reports/oncrpc-peer");
        Path reportDirectory = Path.of(configuredOutput).toAbsolutePath();
        Files.createDirectories(reportDirectory);
        Path log = Files.createTempFile(reportDirectory, "native-", ".log");
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        if (StringUtils.equals(executableProperty, "oncrpc.peer.executable")) {
            boolean hasHost = false;
            boolean server = false;
            for (String argument : arguments) {
                hasHost |= argument.startsWith("--host=");
                server |= StringUtils.equals(argument, "--command=server");
            }
            if (!hasHost) {
                command.add("--host=" + (server ? "0.0.0.0" : ContainerProcess.runnerHost()));
            }
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("METEOR_TEST_RUNNER_HOST", ContainerProcess.runnerHost());
        Process process;
        if (localControl) {
            process = ContainerProcess.startLocal("native", builder);
        } else {
            process = ContainerProcess.start("native", builder);
        }

        // 场景日志记录容器端点与参数，镜像 ID、摘要和平台身份由 ContainerProcess 统一记录。
        try {
            String configuredUsage = System.getProperty("oncrpc.peer.usage.file");
            Path usage = configuredUsage == null ? reportDirectory.resolve("usage.jsonl") : Path.of(configuredUsage);
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("executable", executable);
            identity.put("runtime", "native");
            identity.put("localControl", localControl);
            identity.put("host", ContainerProcess.host(process));
            identity.put("pid", process.pid());
            identity.put("args", List.of(arguments));
            Files.createDirectories(usage.toAbsolutePath().getParent());
            synchronized (USAGE_LOCK) {
                Files.writeString(usage, JSON_MAPPER.writeValueAsString(identity) + "\n", StandardCharsets.UTF_8, //
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return new OncRpcNativeProcess(process, log);
        } catch (IOException | RuntimeException failure) {
            process.destroyForcibly();
            throw failure;
        }
    }

    public long processId() {
        return this.process.pid();
    }

    public int awaitPort() throws InterruptedException {
        // 输出线程负责持续排空日志；启动失败和等待上限都有确定的失败结果。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Native ONC RPC peer startup timed out");
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Native ONC RPC peer exited before listening");
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    public void requireResult(String expected) throws InterruptedException {
        // 进程退出码和精确业务结果共同证明本次互通成功。
        this.requireOutput("RESULT " + expected);
    }

    public void requireExpectedError(String expected) throws InterruptedException {
        // 原生 SDK 必须解码得到预期的标准 RPC 错误，网络超时不能作为成功。
        this.requireOutput("EXPECTED_ERROR " + expected);
    }

    private void requireOutput(String marker) throws InterruptedException {
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw this.failure("Native ONC RPC peer call timed out");
        }
        this.reader.join(1000);
        String completeMarker = marker + "\n";
        if (this.process.exitValue() != 0 || !this.output.toString().contains(completeMarker)) {
            throw this.failure("Native ONC RPC peer did not produce " + marker);
        }
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public String ipv6Host() throws IOException {
        return ipv6(this.host());
    }

    public static String runnerIpv6() throws IOException {
        return ipv6(ContainerProcess.runnerHost());
    }

    private static String ipv6(String host) throws IOException {
        // IPv6 场景必须经过 Docker 双栈网桥；缺少地址时明确失败，不退回 IPv4 或本地回环。
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address instanceof Inet6Address) {
                return address.getHostAddress();
            }
        }
        throw new IOException("Docker test endpoint has no IPv6 address: " + host);
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; native log: " + this.log + "\n" + this.output);
    }

    private void readOutput() {
        // 每行立即落盘，测试失败时保留原生 SDK 的原始诊断信息。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.output.append(line).append('\n');
                Files.writeString(this.log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
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
        // 服务端通过 stdin EOF 退出；异常路径也回收本对象创建的进程和输出线程。
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
