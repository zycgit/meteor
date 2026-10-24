/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 测试独占的 Nacos 单机容器，注册数据随容器关闭释放。 */
public final class NacosTestServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path    home;
    private       Process process;

    public NacosTestServer(Path workDirectory) throws IOException {
        this.home = Files.createDirectories(workDirectory);
    }

    public void start() throws Exception {
        // 使用发行镜像的前台入口，日志仍保存在本轮共享工作目录。
        ProcessBuilder command = new ProcessBuilder("sh", "/home/nacos/bin/docker-startup.sh");
        command.directory(this.home.toFile());
        command.redirectErrorStream(true);
        Path output = this.home.resolve("startup.log");
        command.redirectOutput(output.toFile());
        Map<String, String> environment = command.environment();
        environment.put("MODE", "standalone");
        environment.put("JVM_XMS", "128m");
        environment.put("JVM_XMX", "512m");
        environment.put("JVM_XMN", "64m");
        Path serverLogs = Files.createDirectories(this.home.resolve("server-logs"));
        String javaOptions = "-XX:ActiveProcessorCount=2 -Dnacos.naming.service.metadata.length=65536";
        javaOptions += " -Dnacos.logs.path=" + serverLogs;
        // 官方入口将 JAVA_OPT_EXT 放在 -jar 后面；JVM 选项必须通过 JAVA_TOOL_OPTIONS 交给 java。
        String inheritedOptions = environment.getOrDefault("JAVA_TOOL_OPTIONS", "");
        environment.put("JAVA_TOOL_OPTIONS", inheritedOptions + " " + javaOptions);
        environment.put("NACOS_AUTH_ENABLE", "false");
        environment.put("NACOS_AUTH_ADMIN_ENABLE", "false");
        environment.put("NACOS_AUTH_CONSOLE_ENABLE", "false");
        // 官方容器入口要求这三项完整，即便测试显式关闭鉴权；此凭据仅用于隔离测试环境。
        environment.put("NACOS_AUTH_TOKEN", "bWV0ZW9yLWRvY2tlci1vbmx5LWF1dGgtc2lnbmluZy1rZXktMjAyNg==");
        environment.put("NACOS_AUTH_IDENTITY_KEY", "meteor-docker-test");
        environment.put("NACOS_AUTH_IDENTITY_VALUE", "meteor-docker-test");
        this.process = ContainerProcess.start("nacos", command);

        try {
            // 官方 readiness API 确认存储可读，客户端 gRPC 端口同时可达后才交给应用。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (this.process.isAlive() && System.nanoTime() < deadline) {
                if (this.ready() && this.listening(9848)) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            throw new IOException("Nacos standalone did not start:\n" + Files.readString(output));
        } catch (Exception failure) {
            try {
                this.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public String address() {
        return ContainerProcess.host(this.process) + ":8848";
    }

    @Override
    public void close() throws Exception {
        if (this.process == null) {
            return;
        }
        this.process.destroy();
        if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("Nacos container did not stop");
            }
        }
    }

    private boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ContainerProcess.host(this.process), port), 200);
            return true;
        } catch (IOException notReady) {
            return false;
        }
    }

    private boolean ready() {
        String host = ContainerProcess.host(this.process);
        URI endpoint = URI.create("http://" + host + ":8848/nacos/v3/admin/core/state/readiness");
        HttpURLConnection connection = null;
        try {
            // 健康检查直接访问测试桥接网络，不继承宿主代理或只以端口握手代替服务就绪。
            connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            try (var input = connection.getInputStream()) {
                JsonNode response = JSON.readTree(input);
                if (response == null) {
                    return false;
                }
                boolean successful = response.path("code").asInt(-1) == 0;
                return successful && StringUtils.equals("ok", response.path("data").asText());
            }
        } catch (IOException notReady) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
