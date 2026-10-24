/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.container;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.peer.container.MeteorOrderContainerMain;

/** 只负责创建、观察和释放独占应用容器，不在测试线程内运行订单业务。 */
final class MeteorOrderContainerProcess implements AutoCloseable {
    private final ContainerProcess process;
    private final Path             home;
    private final Path             log;

    private MeteorOrderContainerProcess(ContainerProcess process, Path home, Path log) {
        this.process = process;
        this.home = home;
        this.log = log;
    }

    static MeteorOrderContainerProcess start(String role, Path home, String address) throws Exception {
        // 测试编译输出和依赖缓存由容器共享卷提供，类路径由 Gradle 已解析结果直接传入。
        String classpath = System.getProperty("meteor.test.container.javaClasspath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("meteor.test.container.javaClasspath is required");
        }
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-DlimitTime=1000");
        Path sdkLogs = Files.createDirectories(home.resolve(role + "-sdk-logs"));
        command.add("-DJM.LOG.PATH=" + sdkLogs);
        command.add("-cp");
        command.add(classpath);
        command.add(MeteorOrderContainerMain.class.getName());
        command.add(role);
        command.add(home.toString());
        if (address != null) {
            command.add(address);
        }

        // 每个角色拥有独立容器和日志；控制文件仅位于明确共享的工作目录。
        Path log = home.resolve(role + ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        ContainerProcess process = ContainerProcess.start("java", builder);
        return new MeteorOrderContainerProcess(process, home, log);
    }

    Properties awaitReady() throws Exception {
        // 就绪由提供者发布完成后创建文件通知，不用固定休眠推测启动已经完成。
        Path ready = this.home.resolve("ready.properties");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try (WatchService watcher = this.home.getFileSystem().newWatchService()) {
            this.home.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            while (!Files.exists(ready)) {
                if (!this.process.isAlive()) {
                    throw new IllegalStateException("Provider exited before publishing readiness: " + this.log);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Provider did not publish readiness: " + this.log);
                }
                long pause = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(500));
                var event = watcher.poll(pause, TimeUnit.NANOSECONDS);
                if (event != null) {
                    event.pollEvents();
                    event.reset();
                }
            }
        }
        return read(ready);
    }

    String directAddress(Properties ready) throws Exception {
        // 直连使用提供者容器的 DNS 身份和实际端口，不依赖 runner 的 loopback 或端口映射。
        InterAddress bound = new InterAddress(ready.getProperty("address"));
        String host = ContainerProcess.host(this.process);
        InterAddress address = new InterAddress(bound.getSchema(), host, bound.getPort(), bound.getFormUnit());
        return address.toHostSchema();
    }

    Properties awaitResult() throws Exception {
        // 消费者完成网络调用并关闭上下文后退出，失败时保留各自日志和控制文件。
        if (!this.process.waitFor(90, TimeUnit.SECONDS)) {
            throw new TimeoutException("Consumer did not finish: " + this.log);
        }
        if (this.process.exitValue() != 0) {
            throw new IllegalStateException("Consumer failed with exit " + this.process.exitValue() + ": " + this.log);
        }
        return read(this.home.resolve("result.properties"));
    }

    private static Properties read(Path path) throws Exception {
        Properties result = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    @Override
    public void close() throws Exception {
        // 创建者先通知应用正常退出；超时后释放其独占容器，不影响借用的注册库。
        this.process.getOutputStream().close();
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            this.process.waitFor(15, TimeUnit.SECONDS);
        }
    }
}
