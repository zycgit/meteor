/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final Set<Integer>    allocatedPorts  = new HashSet<>();
    private final Set<Thread>     originalThreads = Thread.getAllStackTraces().keySet();

    protected final int port() throws IOException {
        while (true) {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                int port = socket.getLocalPort();
                if (this.allocatedPorts.add(port)) {
                    return port;
                }
            }
        }
    }

    protected final MetConfiguration configuration() {
        return this.configuration(0);
    }

    protected final MetConfiguration configuration(int port) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", port).protocol("rsf");
        return configuration;
    }

    protected final URLClassLoader spiLoader(String provider) throws IOException {
        Path root = this.temporary.newFolder().toPath();
        Path descriptor = root.resolve("META-INF/services/" + SerializeCoder.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, provider, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[] { root.toUri().toURL() }, this.getClass().getClassLoader());
    }

    @After
    public final void checkThreadsAfterTest() throws Exception {
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
        if (!remaining.isEmpty()) {
            fail("Meteor threads leaked: " + remaining);
        }
    }

    protected static Throwable assertFailure(MetFuture future) throws Exception {
        try {
            future.getData(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected failed call");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    public interface Echo {
        String echo(String value);

        int add(int left, int right);

        String fail();

        String slow(int millis) throws InterruptedException;

        int[] array(int[] values);
    }

    public static class EchoService implements Echo {
        public String echo(String value) {
            return value;
        }

        public int add(int left, int right) {
            return left + right;
        }

        public String fail() {
            throw new IllegalStateException("service failure");
        }

        public String slow(int millis) throws InterruptedException {
            Thread.sleep(millis);
            return "done";
        }

        public int[] array(int[] values) {
            return values;
        }
    }

    public interface Signal {
        void accept(String value) throws InterruptedException;
    }

}
