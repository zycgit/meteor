/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 启动探测区分监听、进程存活和 ZooKeeper 真正开始服务。 */
public class ZookeeperReadinessTest {
    @Test(timeout = 10000)
    public void runningStandaloneServerIsReady() throws Exception {
        // 应用状态来自 srvr 的完整 Mode 行。
        String response = "Zookeeper version: 3.9.6\nMode: standalone\nNode count: 5\n";
        assertTrue(probeResponse(response));
    }

    @Test(timeout = 10000)
    public void listeningServerStillInitializingIsNotReady() throws Exception {
        // 端口可连接时，服务器仍可能处于数据库初始化阶段。
        String response = "This ZooKeeper instance is not currently serving requests\n";
        assertFalse(probeResponse(response));
    }

    @Test(timeout = 10000)
    public void processLivenessDoesNotCountAsApplicationReadiness() throws Exception {
        // ruok 的存活回复不能放行观察会话。
        assertFalse(probeResponse("imok\n"));
    }

    @Test(timeout = 10000)
    public void similarModeTextDoesNotCountAsApplicationReadiness() throws Exception {
        // 只接受完整状态行，不能因为文本包含 standalone 就误判。
        assertFalse(probeResponse("Mode: not-standalone\n"));
    }

    @Test(timeout = 10000)
    public void silentServerHasBoundedProbeTimeout() throws Exception {
        // 对端接受连接并读取探测，但通过信号保持响应未完成。
        ExecutorService responder = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<String> request = responder.submit(() -> {
                try (Socket connection = server.accept()) {
                    byte[] command = connection.getInputStream().readNBytes(4);
                    release.await();
                    return new String(command, StandardCharsets.US_ASCII);
                }
            });

            // 读取必须受探测预算限制，不能把端口连通当作就绪。
            InetSocketAddress endpoint = new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
            PrintWriter diagnostics = new PrintWriter(new StringWriter());
            assertThrows(SocketTimeoutException.class, () -> {
                ZookeeperTestServer.probeServing(endpoint, 100, diagnostics);
            });
            release.countDown();
            assertEquals("srvr", request.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            responder.shutdownNow();
            assertTrue(responder.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static boolean probeResponse(String response) throws Exception {
        // 只替身四字探测端口；业务注册会话仍由真实 ZooKeeper 场景验证。
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<String> request = responder.submit(() -> {
                try (Socket connection = server.accept()) {
                    byte[] command = connection.getInputStream().readNBytes(4);
                    connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    return new String(command, StandardCharsets.US_ASCII);
                }
            });
            InetSocketAddress endpoint = new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
            PrintWriter diagnostics = new PrintWriter(new StringWriter());
            boolean ready = ZookeeperTestServer.probeServing(endpoint, 5000, diagnostics);
            assertEquals("srvr", request.get(5, TimeUnit.SECONDS));
            return ready;
        } finally {
            responder.shutdownNow();
            assertTrue(responder.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
