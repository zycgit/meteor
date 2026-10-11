/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 测试独占的 Nacos 单机进程，使用内置 Derby，关闭后由测试清理工作目录。 */
final class NacosStandaloneServer implements AutoCloseable {
    private final Path    home;
    private final int     port;
    private       Process process;

    NacosStandaloneServer(Path home) throws IOException {
        this.home = home;
        this.port = availablePort();
    }

    void start() throws Exception {
        // 直接运行发行包中的 Java 服务，避免依赖 Docker 或平台启动脚本。
        String java = ProcessHandle.current().info().command().orElseThrow();
        String serverJar = System.getProperty("meteor.test.nacos.serverJar");
        Path output = this.home.resolve("startup.log");
        // 临时记录包含实例快照，明确配置 Naming 元数据容量；部署要求同步记录在接入文档。
        ProcessBuilder command = new ProcessBuilder(java, "-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=2", //
                "-Dfile.encoding=UTF-8", "-Dnacos.standalone=true", "-Dnacos.deployment.type=server", //
                "-Dnacos.naming.service.metadata.length=65536", //
                "-Dnacos.home=" + this.home, "-jar", serverJar, //
                "--nacos.server.main.port=" + this.port, "--nacos.server.ip=127.0.0.1", //
                "--server.address=127.0.0.1", "--nacos.core.auth.enabled=false", //
                "--nacos.core.auth.admin.enabled=false", "--nacos.core.auth.console.enabled=false");
        command.directory(this.home.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(output.toFile());
        this.process = command.start();

        // 等待服务完成初始化；提前退出或超时均携带启动日志使测试明确失败。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (this.process.isAlive() && System.nanoTime() < deadline) {
            String log = Files.readString(output);
            if (log.contains("Nacos started successfully in stand alone mode")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IOException("Nacos standalone did not start:\n" + Files.readString(output));
    }

    String address() {
        return "127.0.0.1:" + this.port;
    }

    @Override
    public void close() throws Exception {
        if (this.process == null) {
            return;
        }
        this.process.destroy();
        try {
            if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Nacos standalone process did not stop: " + this.process.pid());
                }
            }
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
            }
        }
    }

    private static int availablePort() throws IOException {
        // Nacos 从主端口派生 gRPC 和 Raft 端口，须一起检查，不能分别随机分配。
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int attempt = 0; attempt < 100; attempt++) {
            try (ServerSocket main = new ServerSocket(0, 1, loopback)) {
                int port = main.getLocalPort();
                if (port <= 1000 || port > 64534) {
                    continue;
                }
                try (ServerSocket client = new ServerSocket(port + 1000, 1, loopback); //
                     ServerSocket cluster = new ServerSocket(port + 1001, 1, loopback); //
                     ServerSocket raft = new ServerSocket(port - 1000, 1, loopback)) {
                    return port;
                } catch (IOException occupied) {
                    // 派生端口已被占用，重新选择主端口。
                }
            }
        }
        throw new IOException("No available Nacos port group");
    }
}
