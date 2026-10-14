/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.MetConnector;
import net.hasor.meteor.connector.MetListen;
import net.hasor.meteor.support.connector.protocol.RawSocketTestProtocol;
import net.hasor.meteor.support.connector.protocol.SocketTestSupport;
import net.hasor.meteor.domain.payload.RequestPayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** 以真实 TCP 对端验证连接器 SPI 的写入、排空和监听生命周期。 */
public class SocketLifecycleTest {
    @Test(timeout = 15000)
    public void cancelledSendStillDrainsItsPhysicalWrite() throws Exception {
        // 用大报文制造尚未完成的物理写入，对端先只读取一个字节。
        ConnectorConfig config = SocketTestSupport.tcpConfig();
        try (ServerSocket listener = new ServerSocket(0); //
             ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            listener.setSoTimeout(3000);
            InterAddress target = this.address(listener.getLocalPort());
            MetChannel channel = manager.connect(target).get(3, TimeUnit.SECONDS);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                Future<MetChannel> sent = channel.sendData(this.largeRequest());
                assertEquals(37, peer.getInputStream().read());
                assertFalse(sent.isDone());

                // 取消调用方等待后开始排空，已提交字节仍须写完，新写入必须拒绝。
                assertTrue(sent.cancel());
                Future<MetChannel> closed = channel.drainAndClose();
                assertFalse(closed.isDone());
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> {
                    channel.sendData(this.largeRequest()).get(1, TimeUnit.SECONDS);
                });
                assertTrue(rejected.getCause() instanceof IllegalStateException);

                // 对端读到 EOF 证明物理写入已排空，关闭 Future 保持同一身份。
                byte[] buffer = new byte[65536];
                int total = 1;
                int count;
                while ((count = peer.getInputStream().read(buffer)) != -1) {
                    total += count;
                }
                assertEquals(RawSocketTestProtocol.WRITE_SIZE, total);
                assertSame(channel, closed.get(3, TimeUnit.SECONDS));
                assertSame(closed, channel.close());
                assertFalse(channel.isActive());
            }
        }
    }

    @Test(timeout = 10000)
    public void hardCloseInterruptsWriteWithoutWaitingForPeerToRead() throws Exception {
        // 对端保留未读取的大报文，让硬关闭作用于未完成的物理写入。
        ConnectorConfig config = SocketTestSupport.tcpConfig();
        try (ServerSocket listener = new ServerSocket(0); //
             ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            listener.setSoTimeout(3000);
            InterAddress target = this.address(listener.getLocalPort());
            MetChannel channel = manager.connect(target).get(3, TimeUnit.SECONDS);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Future<MetChannel> sent = channel.sendData(this.largeRequest());
                assertEquals(37, peer.getInputStream().read());
                assertFalse(sent.isDone());

                // 无需对端继续读取，硬关闭即完成，后续排空返回同一个关闭结果。
                Future<MetChannel> closed = channel.close();
                assertSame(channel, closed.get(3, TimeUnit.SECONDS));
                assertSame(closed, channel.drainAndClose());
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    sent.get(3, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(channel.isActive());
            }
        }
    }

    @Test(timeout = 10000)
    public void closeBindLeavesExistingConnectionsUsable() throws Exception {
        // 两个连接器通过真实 TCP 建立连接，之后只停止服务端接纳新连接。
        ConnectorConfig serverConfig = SocketTestSupport.tcpConfig();
        ConnectorConfig clientConfig = SocketTestSupport.tcpConfig();
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            MetListen listen = server.bind(serverConfig.name()).get(3, TimeUnit.SECONDS);
            MetChannel channel = client.connect(listen.getBindAddress()).get(3, TimeUnit.SECONDS);
            MetConnector connector = server.find(serverConfig.name());

            // 关闭监听不关闭已有连接，已有通道仍可提交一次物理写入。
            connector.closeBind();
            assertFalse(listen.isActive());
            assertTrue(connector.getListenList().isEmpty());
            Future<MetChannel> sent = channel.sendData(new RequestPayload());
            assertSame(channel, sent.get(3, TimeUnit.SECONDS));
            assertTrue(channel.isActive());
        }
    }

    @Test(timeout = 10000)
    public void closeBindPreventsRebinding() throws Exception {
        // 先真实绑定端口，再永久停止该连接器的监听接纳。
        ConnectorConfig config = SocketTestSupport.tcpConfig();
        try (ConnectorManager server = SocketTestSupport.manager(config)) {
            server.init();
            MetListen listen = server.bind(config.name()).get(3, TimeUnit.SECONDS);
            MetConnector connector = server.find(config.name());
            connector.closeBind();
            assertFalse(listen.isActive());
            assertTrue(connector.getListenList().isEmpty());

            // 同一个连接器不能通过再次 bind 恢复监听。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                server.bind(config.name()).get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
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
