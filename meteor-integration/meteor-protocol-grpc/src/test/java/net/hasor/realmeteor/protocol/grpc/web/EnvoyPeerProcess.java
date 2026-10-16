/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从固定镜像提取真实 Envoy，在独占进程中将 gRPC-Web 转发到本次测试的 gRPC 服务。 */
final class EnvoyPeerProcess implements AutoCloseable {
    private static final String  IMAGE = "envoyproxy/envoy@sha256:" //
            + "08acc53db6f4ac46e77e36b203c5254b4a2549869314bb54cf97aab81baa4566";
    private final        Path    directory;
    private final        int     port;
    private              Process process;

    EnvoyPeerProcess(int upstreamPort) throws Exception {
        this.directory = Files.createTempDirectory("meteor-grpc-web-envoy-");
        try {
            // 镜像只提供固定版本的二进制；本地进程避免 Docker Desktop 回环网络差异。
            Path executable = this.extractExecutable();
            Path configuration = this.directory.resolve("envoy.yaml");
            Path adminAddress = this.directory.resolve("admin.address");
            Files.writeString(configuration, this.configuration(upstreamPort));
            List<String> arguments = List.of(executable.toString(), //
                    "--config-path", configuration.toString(), "--admin-address-path", adminAddress.toString(), //
                    "--disable-hot-restart", "--concurrency", "1", "--log-level", "warning");
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
            builder.redirectOutput(this.directory.resolve("envoy.log").toFile());
            this.process = builder.start();
            this.port = this.awaitListener();
        } catch (Exception failure) {
            // 初始化失败也清理本次创建的进程，不操作其他 Envoy。
            try {
                this.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    int getPort() {
        return this.port;
    }

    private Path extractExecutable() throws Exception {
        // 容器从不启动，仅复制固定 digest 的可执行文件，随后立即删除本次容器。
        String name = "meteor-grpc-web-" + UUID.randomUUID();
        Path executable = this.directory.resolve("envoy");
        try (AutoCloseable cleanup = () -> {
            boolean interrupted = Thread.interrupted();
            try {
                this.command(List.of("docker", "rm", "--force", name), true);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }) {
            this.command(List.of("docker", "create", "--pull", "never", "--name", name, IMAGE), true);
            this.command(List.of("docker", "cp", name + ":/usr/local/bin/envoy", executable.toString()), true);
            return executable;
        }
    }

    private int awaitListener() throws Exception {
        // 读取 Envoy 写出的动态管理地址，再查询已激活监听器，避免预占端口竞争。
        Path addressFile = this.directory.resolve("admin.address");
        HttpClient admin = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        ObjectMapper json = new ObjectMapper();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!this.process.isAlive()) {
                break;
            }
            try {
                if (Files.exists(addressFile)) {
                    String address = Files.readString(addressFile).trim();
                    if (address.isEmpty()) {
                        TimeUnit.MILLISECONDS.sleep(50);
                        continue;
                    }
                    URI listeners = URI.create("http://" + address + "/listeners?format=json");
                    HttpRequest request = HttpRequest.newBuilder(listeners).timeout(Duration.ofSeconds(1)).build();
                    var response = admin.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonNode statuses = json.readTree(response.body()).path("listener_statuses");
                    for (JsonNode status : statuses) {
                        JsonNode socket = status.path("local_address").path("socket_address");
                        int listenerPort = socket.path("port_value").asInt();
                        if (listenerPort > 0) {
                            return listenerPort;
                        }
                    }
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        String logs = Files.readString(this.directory.resolve("envoy.log"));
        throw new IOException("Envoy listener did not become ready: " + logs, lastFailure);
    }

    private String configuration(int upstreamPort) {
        String connectionManagerType = "type.googleapis.com/envoy.extensions.filters.network." //
                + "http_connection_manager.v3.HttpConnectionManager";
        return """
                admin:
                  address:
                    socket_address: { address: 127.0.0.1, port_value: 0 }
                static_resources:
                  listeners:
                    - name: grpc_web
                      address:
                        socket_address: { address: 127.0.0.1, port_value: 0 }
                      filter_chains:
                        - filters:
                            - name: envoy.filters.network.http_connection_manager
                              typed_config:
                                "@type": %s
                                stat_prefix: grpc_web
                                route_config:
                                  name: grpc_web_routes
                                  virtual_hosts:
                                    - name: grpc_backend
                                      domains: ["*"]
                                      routes:
                                        - match: { prefix: "/web/" }
                                          route: { cluster: grpc_backend, prefix_rewrite: "/", timeout: 10s }
                                http_filters:
                                  - name: envoy.filters.http.grpc_web
                                    typed_config:
                                      "@type": type.googleapis.com/envoy.extensions.filters.http.grpc_web.v3.GrpcWeb
                                  - name: envoy.filters.http.router
                                    typed_config:
                                      "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                    - name: grpc_backend
                      type: STATIC
                      connect_timeout: 2s
                      typed_extension_protocol_options:
                        envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                          "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                          explicit_http_config:
                            http2_protocol_options: {}
                      load_assignment:
                        cluster_name: grpc_backend
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address: { address: 127.0.0.1, port_value: %d }
                """.formatted(connectionManagerType, upstreamPort);
    }

    private String command(List<String> arguments, boolean requireSuccess) throws Exception {
        // 命令输出直接落盘，等待有明确上限，避免管道缓冲区阻塞或 Docker 进程残留。
        Path output = Files.createTempFile(this.directory, "command-", ".log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        Process process = builder.redirectOutput(output.toFile()).start();
        try (AutoCloseable cleanup = () -> this.terminate(process)) {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("Docker command timed out: " + arguments);
            }
            String result = Files.readString(output, StandardCharsets.UTF_8);
            if (requireSuccess && process.exitValue() != 0) {
                throw new IOException("Docker command failed: " + result);
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private void terminate(Process process) throws IOException {
        // 中断不能跳过子进程回收；有界等待结束后恢复调用线程的中断标记。
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Peer process did not exit after forced termination");
                }
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() throws Exception {
        // 清理动作由资源语法保存异常关系，关闭失败不会被文件删除失败覆盖。
        try (AutoCloseable filesCleanup = () -> {
            if (Files.exists(this.directory)) {
                try (var files = Files.walk(this.directory)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }) {
            if (this.process != null) {
                try (AutoCloseable processCleanup = () -> this.terminate(this.process)) {
                    this.process.destroy();
                    this.process.waitFor(3, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }
}
