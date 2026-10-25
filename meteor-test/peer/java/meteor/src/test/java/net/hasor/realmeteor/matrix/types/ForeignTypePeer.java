/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
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

/** 拥有 Node/Python 类型互通对端，控制管道只传递启动通知与单场景结果。 */
public final class ForeignTypePeer implements AutoCloseable {
    private final Process         process;
    private final BufferedReader  output;
    private final ExecutorService reader;
    private final Path            log;

    private ForeignTypePeer(String language, String protocol, List<String> arguments) throws IOException {
        // 对端脚本由各语言镜像执行，业务请求不经过 Java 的 HTTP 或协议客户端。
        List<String> command = new ArrayList<>();
        if (StringUtils.equals(language, "node")) {
            Path directory = Path.of(System.getProperty(protocol + ".peer.dir", "/opt/meteor-peer/node/" + protocol));
            command.add("node");
            command.add(directory.resolve("types-peer.js").toString());
        } else if (StringUtils.equals(language, "python") && StringUtils.equals(protocol, "jsonrpc")) {
            command.add("python3");
            command.add("-u");
            command.add(Path.of("peer/python/jsonrpc/types_peer.py").toAbsolutePath().toString());
        } else {
            throw new IllegalArgumentException("Unsupported foreign type peer: " + language + "/" + protocol);
        }
        command.addAll(arguments);
        Path logs = Files.createDirectories(Path.of("build/peer-logs/type-matrix", language + "-" + protocol));
        this.log = Files.createTempFile(logs, "peer-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(this.log.toFile());
        this.process = ContainerProcess.start(language, builder);
        this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
        this.reader = Executors.newSingleThreadExecutor();
    }

    public static ForeignTypePeer provider(String language, String protocol) throws IOException {
        Path vectors = Path.of("src/test/resources/vectors/types/values.json").toAbsolutePath();
        return new ForeignTypePeer(language, protocol, List.of("provider", vectors.toString()));
    }

    public static ForeignTypePeer consumer(String language, String protocol, String endpoint, String vector) throws IOException {
        Path vectors = Path.of("src/test/resources/vectors/types/values.json").toAbsolutePath();
        List<String> arguments = List.of("consumer", vectors.toString(), endpoint, vector);
        return new ForeignTypePeer(language, protocol, arguments);
    }

    public int awaitPort() throws Exception {
        // 端口来自独立服务端自己的监听结果，启动等待有明确上限。
        String line = this.reader.submit(this.output::readLine).get(15, TimeUnit.SECONDS);
        if (line == null || !line.startsWith("READY ")) {
            throw new IOException("Foreign type listener failed: " + line + "\n" + Files.readString(this.log));
        }
        return Integer.parseInt(line.substring(6));
    }

    public String host() {
        return ContainerProcess.host(this.process);
    }

    public void requireSuccess(String marker) throws Exception {
        // 正常退出和当前类型对应的标记必须同时满足，限制验证不会伪装成原生通过。
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
            throw new IOException("Foreign type consumer timed out:\n" + Files.readString(this.log));
        }
        String result = this.reader.submit(this.output::readLine).get(5, TimeUnit.SECONDS);
        if (this.process.exitValue() != 0 || !StringUtils.equals(marker, result)) {
            throw new AssertionError("Foreign type consumer failed: " + result + "\n" + Files.readString(this.log));
        }
    }

    @Override
    public void close() throws Exception {
        // 当前夹具关闭自己创建的进程，服务端在 stdin EOF 后先尝试正常释放监听。
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
