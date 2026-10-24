/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;

/** 场景独占的 ZooKeeper 容器，各应用通过桥接网络访问同一个注册库。 */
public final class ZookeeperTestServer implements AutoCloseable {
    private final Path    output;
    private final Process process;

    public ZookeeperTestServer() throws Exception {
        // 容器使用自己的数据目录，关闭时由创建方移除容器及数据。
        Path home = Files.createTempDirectory("meteor-zookeeper-");
        this.output = home.resolve("startup.log");
        ProcessBuilder command = new ProcessBuilder( //
                "/docker-entrypoint.sh", "zkServer.sh", "start-foreground");
        command.directory(home.toFile());
        command.redirectErrorStream(true).redirectOutput(this.output.toFile());
        command.environment().put("ZOO_ADMINSERVER_ENABLED", "false");
        command.environment().put("ZOO_MAX_CLIENT_CNXNS", "1000");
        this.process = ContainerProcess.start("zookeeper", command);

        try {
            // 独立观察会话完成真实 ZooKeeper 握手，不能让业务连接重试代替环境就绪检查。
            try (CuratorFramework readiness = CuratorFrameworkFactory.newClient( //
                    this.getConnectString(), new RetryOneTime(100))) {
                readiness.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (this.process.isAlive() && System.nanoTime() < deadline) {
                    if (readiness.blockUntilConnected(100, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            }
            throw new IOException("ZooKeeper container did not start:\n" + Files.readString(this.output));
        } catch (Exception failure) {
            try {
                this.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public String getConnectString() {
        return ContainerProcess.host(this.process) + ":2181";
    }

    @Override
    public void close() throws Exception {
        this.process.destroy();
        if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("ZooKeeper container did not stop");
            }
        }
    }
}
