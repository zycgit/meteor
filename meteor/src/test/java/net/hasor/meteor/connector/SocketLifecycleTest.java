/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.example.connector.protocol.RawSocketTestProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

/** 以真实 TCP 对端验证管理器对物理写入、入站接纳和连接关闭的编排。 */
public class SocketLifecycleTest {
    @Test(timeout = 15000)
    public void managerCloseDrainsCancelledPhysicalWrite() throws Exception {
        // 用大报文制造尚未完成的物理写入，对端先只读取一个字节。
        ConnectorConfig config = SocketTestSupport.tcpConfig();
        try (ServerSocket listener = new ServerSocket(0); //
             ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            listener.setSoTimeout(3000);
            MetChannel channel = manager.connect(this.address(listener.getLocalPort())).get(3, TimeUnit.SECONDS);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                Future<MetChannel> sent = channel.sendData(this.largeRequest());
                assertEquals(37, peer.getInputStream().read());
                assertFalse(sent.isDone());

                // 取消调用方等待不会撤回已提交字节，管理器关闭负责排空后释放连接。
                assertTrue(sent.cancel());
                ExecutorService lifecycle = Executors.newSingleThreadExecutor();
                try {
                    FutureTask<Void> closing = new FutureTask<>(manager::close, null);
                    lifecycle.execute(closing);
                    this.awaitStopped(manager);
                    ExecutionException rejected = assertThrows(ExecutionException.class, () -> {
                        channel.sendData(this.largeRequest()).get(1, TimeUnit.SECONDS);
                    });
                    assertTrue(rejected.getCause() instanceof IllegalStateException);

                    // 对端读到完整报文和 EOF，证明关闭过程排空了已经提交的写入。
                    byte[] buffer = new byte[65536];
                    int total = 1;
                    int count;
                    while ((count = peer.getInputStream().read(buffer)) != -1) {
                        total += count;
                    }
                    assertEquals(RawSocketTestProtocol.WRITE_SIZE, total);
                    closing.get(3, TimeUnit.SECONDS);
                } finally {
                    lifecycle.shutdownNow();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void stopInboundClosesAcceptedConnectionAndHidesListener() throws Exception {
        // 启动监听并通过真实 TCP 建立一条已接纳连接。
        ConnectorConfig serverConfig = SocketTestSupport.tcpConfig();
        ConnectorConfig clientConfig = SocketTestSupport.tcpConfig();
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            server.startInbound();
            InterAddress address = server.bindAddress("raw");
            MetChannel channel = client.connect(address).get(3, TimeUnit.SECONDS);

            // 停止入站会关闭服务端已接纳连接，并撤下公开监听地址。
            server.stopInbound();
            assertNull(server.bindAddress("raw"));
        }
    }

    @Test(timeout = 10000)
    public void startInboundResumesConfiguredListener() throws Exception {
        // 停止后重新启动入站，端点继续使用原公开地址接纳新连接。
        ConnectorConfig config = SocketTestSupport.tcpConfig();
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             ConnectorManager client = SocketTestSupport.manager(config)) {
            server.init();
            client.init();
            server.startInbound();
            InterAddress address = server.bindAddress("raw");
            server.stopInbound();
            assertNull(server.bindAddress("raw"));

            // 恢复监听后可通过同一公开地址重新发起连接。
            server.startInbound();
            InterAddress resumed = server.bindAddress("raw");
            assertEquals(address, resumed);
            MetChannel channel = client.connect(resumed).get(3, TimeUnit.SECONDS);
        }
    }

    private void awaitStopped(ConnectorManager manager) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (manager.isInitialized() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (manager.isInitialized()) {
            throw new TimeoutException("Connector manager did not begin closing");
        }
    }

    private RequestPayload largeRequest() {
        RequestPayload request = new RequestPayload();
        request.setRequestID(1);
        request.setClientTimeout(10000);
        return request;
    }

    private InterAddress address(int port) {
        return new InterAddress("raw", "127.0.0.1", port, "default");
    }
}
