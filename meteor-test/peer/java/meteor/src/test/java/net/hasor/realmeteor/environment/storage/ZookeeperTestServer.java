/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.zookeeper.KeeperException.NoNodeException;

/** 场景借用 Compose 的 ZooKeeper，普通场景独占 chroot，原生 SDK 场景独占租约。 */
public final class ZookeeperTestServer implements AutoCloseable {
    private static final String LOCK_ROOT = "/meteor-test-locks";
    private static final Set<String> ACTIVE_ROOTS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger NATIVE_LEASES = new AtomicInteger();
    private static CuratorFramework sharedObserver;
    private static CuratorFramework nativeObserver;
    private static Path sharedHome;
    private static Path nativeHome;

    private final CuratorFramework observer;
    private final InterProcessMutex lease;
    private final Path home;
    private final String address;
    private final String root;
    private boolean closed;

    private ZookeeperTestServer(boolean dedicated) throws Exception {
        // 外层 Compose 已建立服务；当前 JVM 只创建和关闭自己的观察客户端。
        startShared(dedicated);
        if (dedicated) {
            this.observer = nativeObserver;
            this.home = nativeHome;
            this.address = System.getenv("METEOR_TEST_ZOOKEEPER_NATIVE_ADDRESS");
            this.root = "";
            this.lease = new InterProcessMutex(this.observer, LOCK_ROOT + "/native-registry");
            if (!this.lease.acquire(30, TimeUnit.SECONDS)) {
                throw new IOException("Native ZooKeeper registry lease timed out: " + this.address);
            }
            try {
                // 原生 SDK 丢弃 URL chroot；独占范围内必须没有上个场景的业务数据。
                List<String> roots = this.businessRoots();
                if (!roots.isEmpty()) {
                    throw new IOException("Native ZooKeeper registry contains unreleased data: " + roots);
                }
                NATIVE_LEASES.incrementAndGet();
            } catch (Exception failure) {
                try {
                    this.lease.release();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                recordCleanup(this.home, failure);
                throw failure;
            }
        } else {
            this.observer = sharedObserver;
            this.home = sharedHome;
            this.address = System.getenv("METEOR_TEST_ZOOKEEPER_ADDRESS");
            this.root = "/meteor-test-" + UUID.randomUUID();
            this.lease = null;
            this.observer.create().forPath(this.root);
            ACTIVE_ROOTS.add(this.root);
        }
    }

    public static ZookeeperTestServer shared() throws Exception {
        return new ZookeeperTestServer(false);
    }

    public static ZookeeperTestServer dedicated() throws Exception {
        return new ZookeeperTestServer(true);
    }

    private static synchronized void startShared(boolean dedicated) throws Exception {
        CuratorFramework existing = sharedObserver;
        if (dedicated) {
            existing = nativeObserver;
        }
        if (existing != null) {
            return;
        }

        String key = "METEOR_TEST_ZOOKEEPER_ADDRESS";
        String service = "zookeeper";
        if (dedicated) {
            key = "METEOR_TEST_ZOOKEEPER_NATIVE_ADDRESS";
            service = "zookeeper-native";
        }
        String address = System.getenv(key);
        if (StringUtils.isBlank(address)) {
            throw new IllegalStateException(key + " is required");
        }
        String directory = System.getProperty("meteor.test.container.workDirectory", "build/container-scenarios");
        Path home = Files.createDirectories(Path.of(directory).resolve("registry-services/" + service));
        CuratorFramework observer = ready(address, home);
        try {
            Files.writeString(home.resolve("lifecycle.properties"), "state=borrowed\naddress=" + address + "\n");
            Runtime.getRuntime().addShutdownHook(new Thread( //
                    () -> releaseShared(observer, home), service + "-client-close"));
            if (dedicated) {
                nativeHome = home;
                nativeObserver = observer;
            } else {
                sharedHome = home;
                sharedObserver = observer;
            }
        } catch (Exception failure) {
            observer.close();
            throw failure;
        }
    }

    private static CuratorFramework ready(String address, Path home) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(10);
        URI target = URI.create("zk://" + address);
        String host = target.getHost();
        int port = target.getPort();
        if (host == null || port <= 0) {
            throw new IllegalArgumentException("ZooKeeper address must contain host and port: " + address);
        }
        CuratorFramework observer = null;
        ConcurrentLinkedQueue<String> states = new ConcurrentLinkedQueue<>();
        ConnectionStateListener connectionStates = (client, state) -> {
            states.add("curatorState=" + state + " elapsedMillis=" + elapsedMillis(started));
        };
        UnhandledErrorListener errors = (message, failure) -> {
            states.add("curatorError=" + message + " cause=" + failure);
        };
        try (PrintWriter diagnostics = new PrintWriter(Files.newBufferedWriter(home.resolve("readiness.log")))) {
            diagnostics.println("address=" + address + "\nbudgetMillis=10000");
            diagnostics.flush();

            // 端口监听不代表数据库已就绪，srvr 与真实会话共用有界等待预算。
            boolean serving = false;
            while (System.nanoTime() < deadline) {
                try {
                    InetSocketAddress endpoint = new InetSocketAddress(host, port);
                    diagnostics.println("resolvedEndpoint=" + endpoint);
                    int timeout = (int) Math.min(1000, remainingMillis(deadline));
                    serving = probeServing(endpoint, timeout, diagnostics);
                    diagnostics.println("probeElapsedMillis=" + elapsedMillis(started));
                    diagnostics.flush();
                    if (serving) {
                        break;
                    }
                } catch (IOException unavailable) {
                    diagnostics.println("probeElapsedMillis=" + elapsedMillis(started));
                    unavailable.printStackTrace(diagnostics);
                    diagnostics.flush();
                }
                TimeUnit.MILLISECONDS.sleep(Math.min(100, remainingMillis(deadline)));
            }
            if (!serving) {
                throw startupFailure(address, home, "application readiness");
            }

            // 观察客户端归当前 JVM 持有，释放它不会关闭 Compose 服务。
            observer = CuratorFrameworkFactory.newClient(address, new RetryOneTime(100));
            observer.getConnectionStateListenable().addListener(connectionStates);
            observer.getUnhandledErrorListenable().addListener(errors);
            diagnostics.println("curatorStartElapsedMillis=" + elapsedMillis(started));
            diagnostics.flush();
            observer.start();
            while (System.nanoTime() < deadline) {
                long wait = Math.min(100, remainingMillis(deadline));
                boolean connected = observer.blockUntilConnected((int) wait, TimeUnit.MILLISECONDS);
                drainStates(states, diagnostics);
                if (connected) {
                    diagnostics.println("readyElapsedMillis=" + elapsedMillis(started));
                    return observer;
                }
            }
            throw startupFailure(address, home, "Curator session handshake");
        } catch (Exception failure) {
            if (observer != null) {
                observer.close();
            }
            throw failure;
        } finally {
            if (observer != null) {
                observer.getConnectionStateListenable().removeListener(connectionStates);
                observer.getUnhandledErrorListenable().removeListener(errors);
            }
        }
    }

    static boolean probeServing(InetSocketAddress endpoint, int timeout, PrintWriter diagnostics) throws IOException {
        // srvr 在服务未运行时返回状态文本；ruok 的 imok 不代表可以建立会话。
        try (Socket socket = new Socket()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            socket.connect(endpoint, timeout);
            socket.getOutputStream().write("srvr".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // 逐段读取也共用探测截止时间，部分响应不能反复刷新读取预算。
            InputStream response = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout((int) remainingMillis(deadline));
                int next = response.read();
                if (next == '\n' || next == -1) {
                    String text = line.toString();
                    diagnostics.println("srvr=" + text);
                    if (StringUtils.equals("Mode: standalone", text)) {
                        return true;
                    }
                    if (next == -1) {
                        return false;
                    }
                    line.setLength(0);
                } else if (next != '\r') {
                    line.append((char) next);
                }
            }
            throw new SocketTimeoutException("ZooKeeper srvr response exceeded probe budget");
        }
    }

    private static long remainingMillis(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void drainStates(ConcurrentLinkedQueue<String> states, PrintWriter diagnostics) {
        String state;
        while ((state = states.poll()) != null) {
            diagnostics.println(state);
        }
        diagnostics.flush();
    }

    private static IOException startupFailure(String address, Path home, String phase) {
        String message = "Compose ZooKeeper did not complete " + phase + "; address=" + address;
        return new IOException(message + "; diagnostics=" + home.resolve("readiness.log"));
    }

    public String getConnectString() {
        return this.address + this.root;
    }

    public void release(String path) throws Exception {
        // 调用方提供的是场景根以内的路径，不能影响其他句柄的 chroot。
        try {
            this.observer.delete().deletingChildrenIfNeeded().forPath(this.root + path);
        } catch (NoNodeException absent) {
            // 场景已删除该路径时，清理目标已经达成。
        }
    }

    @Override
    public void close() throws Exception {
        if (this.closed) {
            return;
        }
        Exception failure = null;
        try {
            if (this.lease == null) {
                this.release("");
                ACTIVE_ROOTS.remove(this.root);
            } else {
                // 当前租约独占原生服务，只删除业务根，锁与 ZooKeeper 自有节点保留。
                for (String businessRoot : this.businessRoots()) {
                    try {
                        this.release("/" + businessRoot);
                    } catch (Exception cleanup) {
                        if (failure == null) {
                            failure = cleanup;
                        } else {
                            failure.addSuppressed(cleanup);
                        }
                    }
                }
            }
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        } finally {
            if (this.lease != null) {
                try {
                    this.lease.release();
                    NATIVE_LEASES.decrementAndGet();
                } catch (Exception cleanup) {
                    if (failure == null) {
                        failure = cleanup;
                    } else {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            this.closed = true;
        }
        if (failure != null) {
            recordCleanup(this.home, failure);
            throw failure;
        }
    }

    private List<String> businessRoots() throws Exception {
        List<String> roots = this.observer.getChildren().forPath("/");
        roots.remove("zookeeper");
        roots.remove(LOCK_ROOT.substring(1));
        return roots;
    }

    private static void releaseShared(CuratorFramework observer, Path home) {
        Exception failure = null;
        if (observer == sharedObserver && !ACTIVE_ROOTS.isEmpty()) {
            failure = new IOException("ZooKeeper roots were not released: " + ACTIVE_ROOTS);
        }
        if (observer == nativeObserver && NATIVE_LEASES.get() != 0) {
            failure = new IOException("Native ZooKeeper leases were not released: " + NATIVE_LEASES.get());
        }
        try {
            observer.close();
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        recordCleanup(home, failure);
        if (failure != null) {
            throw new IllegalStateException("Cannot release ZooKeeper client", failure);
        }
    }

    private static synchronized void recordCleanup(Path home, Exception failure) {
        // 已发生的清理失败不可被随后成功的客户端关闭覆盖。
        try {
            Path lifecycle = home.resolve("lifecycle.properties");
            boolean failed = failure != null || Files.readString(lifecycle).startsWith("state=failed\n");
            String state = "state=released\n";
            if (failed) {
                state = "state=failed\n";
            }
            if (failure != null) {
                try (PrintWriter error = new PrintWriter( //
                        Files.newBufferedWriter(home.resolve("cleanup-error.log")))) {
                    failure.printStackTrace(error);
                }
            }
            Files.writeString(lifecycle, state);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Cannot record registry service cleanup", unavailable);
        }
    }
}
