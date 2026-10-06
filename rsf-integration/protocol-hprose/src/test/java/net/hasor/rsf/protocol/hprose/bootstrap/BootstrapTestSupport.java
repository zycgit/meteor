/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
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
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfFuture;
import net.hasor.rsf.address.route.ArgsKey;
import net.hasor.rsf.bootstrap.Configuration;
import net.hasor.rsf.serialize.SerializeCoder;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.fail;

/** Test-owned context construction and cleanup; no separate network manager or production test hook. */
public abstract class BootstrapTestSupport {
    @Rule
    public        TemporaryFolder  temporary       = new TemporaryFolder();
    private final List<RsfContext> contexts        = new ArrayList<>();
    private final Set<Integer>     allocatedPorts  = new HashSet<>();
    private final Set<Thread>      originalThreads = Thread.getAllStackTraces().keySet();

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

    protected final Configuration configuration() throws IOException {
        return this.configuration(this.port());
    }

    protected final Configuration configuration(int port) {
        Configuration configuration = new Configuration().setSerializeType("Java");
        configuration.connector("tcp").bind("127.0.0.1", port).protocol("rsf");
        return configuration;
    }

    protected final RsfContext context(Configuration configuration) throws IOException {
        RsfContext context = configuration.buildContext(this.getClass().getClassLoader());
        this.contexts.add(context);
        return context;
    }

    protected final URLClassLoader spiLoader(String provider) throws IOException {
        Path root = this.temporary.newFolder().toPath();
        Path descriptor = root.resolve("META-INF/services/" + SerializeCoder.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, provider, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[] { root.toUri().toURL() }, this.getClass().getClassLoader());
    }

    @After
    public final void closeContextsAndCheckThreads() throws Exception {
        try {
            for (int index = this.contexts.size() - 1; index >= 0; index--) {
                this.contexts.get(index).close();
            }
        } finally {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> remaining = new ArrayList<>();
            do {
                remaining.clear();
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (!this.originalThreads.contains(thread) && thread.isAlive() && thread.getName().startsWith("RSF")) {
                        remaining.add(thread.getName());
                    }
                }
                if (remaining.isEmpty()) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            if (!remaining.isEmpty()) {
                fail("RSF threads leaked: " + remaining);
            }
        }
    }

    protected static Throwable assertFailure(RsfFuture future) throws Exception {
        try {
            future.getData(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected failed call");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    @FunctionalInterface
    protected interface ThrowingAction {
        void run() throws Throwable;
    }

    protected static <T extends Throwable> T assertThrows(Class<T> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError("Expected " + type.getName(), failure);
            }
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type.getName());
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

    public static class RoutingArgsKey implements ArgsKey {
        public String eval(String service, String method, Object[] args) {
            return String.valueOf(args[0]);
        }
    }
}
