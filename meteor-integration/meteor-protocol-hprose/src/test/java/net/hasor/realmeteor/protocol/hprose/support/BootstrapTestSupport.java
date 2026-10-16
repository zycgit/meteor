/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.serialize.SerializeCoder;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.fail;

/** 集成用例共用的配置、服务契约和线程泄漏检查；资源由各场景自行关闭。 */
public abstract class BootstrapTestSupport {
    @Rule
    public        TemporaryFolder temporary       = new TemporaryFolder();
    private final Set<Thread>     originalThreads = Thread.getAllStackTraces().keySet();

    protected final int port() throws IOException {
        // 向操作系统申请临时端口，返回前关闭探测 socket。
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    protected final MetConfiguration configuration() {
        return this.configuration(0);
    }

    protected final MetConfiguration configuration(int port) {
        // 只准备 TCP/RSF 配置，实际构建与启动由当前场景负责。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", port).protocol("rsf");
        return configuration;
    }

    protected final URLClassLoader spiLoader(String provider) throws IOException {
        // 为当前场景写入独立 SPI 描述文件，不修改插件正式注册资源。
        Path root = this.temporary.newFolder().toPath();
        Path descriptor = root.resolve("META-INF/services/" + SerializeCoder.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, provider, StandardCharsets.UTF_8);

        // 使用应用类加载器作为父级，只隔离此场景新增的 SPI 描述。
        URL[] resources = { root.toUri().toURL() };
        ClassLoader parent = this.getClass().getClassLoader();
        return new URLClassLoader(resources, parent);
    }

    @After
    public final void checkThreadsAfterTest() throws Exception {
        // 有界等待本场景新建的框架线程退出，不把已有线程计为泄漏。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> remaining = new ArrayList<>();
        do {
            remaining.clear();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                boolean frameworkThread = name.startsWith("RSF") || name.startsWith("Meteor");
                boolean newThread = !this.originalThreads.contains(thread);
                if (newThread && thread.isAlive() && frameworkThread) {
                    remaining.add(thread.getName());
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);

        // 等待结束仍存活的线程需要报告名称，便于定位未关闭的资源。
        if (!remaining.isEmpty()) {
            fail("Meteor threads leaked: " + remaining);
        }
    }

    protected static Throwable assertFailure(MetFuture future) throws Exception {
        // 通过调用方可见的 Future 结果取得异常，保留有界等待。
        try {
            future.getData(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected failed call");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }
}
