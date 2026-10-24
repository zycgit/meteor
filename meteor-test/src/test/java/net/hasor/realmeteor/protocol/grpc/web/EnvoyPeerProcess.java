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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独立 Envoy 容器将 gRPC-Web 转发到测试容器中的标准 gRPC 服务。 */
final class EnvoyPeerProcess implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final        Path         directory;
    private final        int          port;
    private              Process      process;

    EnvoyPeerProcess(int upstreamPort) throws Exception {
        Path runtime = Path.of("build/peer-runtime/envoy").toAbsolutePath();
        Files.createDirectories(runtime);
        this.directory = Files.createTempDirectory(runtime, "grpc-web-");
        try {
            // 配置与动态管理地址位于共享工作卷；Envoy 本体始终在独立网络容器中执行。
            Path configuration = this.directory.resolve("envoy.yaml");
            Path adminAddress = this.directory.resolve("admin.address");
            Files.writeString(configuration, this.configuration(upstreamPort));
            List<String> arguments = List.of("/usr/local/bin/envoy", //
                    "--config-path", configuration.toString(), "--admin-address-path", adminAddress.toString(), //
                    "--disable-hot-restart", "--concurrency", "1", "--log-level", "warning");
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
            builder.redirectOutput(this.directory.resolve("envoy.log").toFile());
            this.process = ContainerProcess.start("envoy", builder);
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

    String getHost() {
        return ContainerProcess.host(this.process);
    }

    long processId() {
        return this.process.pid();
    }

    private int awaitListener() throws Exception {
        // 读取 Envoy 写出的动态管理地址，再查询已激活监听器，避免预占端口竞争。
        Path addressFile = this.directory.resolve("admin.address");
        HttpClient admin = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
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
                    int adminPort = URI.create("http://" + address).getPort();
                    URI listeners = new URI("http", null, this.getHost(), adminPort, "/listeners", "format=json", null);
                    HttpRequest request = HttpRequest.newBuilder(listeners).timeout(Duration.ofSeconds(1)).build();
                    var response = admin.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonNode statuses = MAPPER.readTree(response.body()).path("listener_statuses");
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
                    socket_address: { address: 0.0.0.0, port_value: 0 }
                static_resources:
                  listeners:
                    - name: grpc_web
                      address:
                        socket_address: { address: 0.0.0.0, port_value: 0 }
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
                      type: STRICT_DNS
                      dns_lookup_family: V4_ONLY
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
                                    socket_address: { address: %s, port_value: %d }
                """.formatted(connectionManagerType, ContainerProcess.runnerHost(), upstreamPort);
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
