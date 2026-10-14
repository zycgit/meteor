/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.ConnectorSubscriber;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.MetConnector;
import net.hasor.meteor.connector.MetListen;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.support.connector.protocol.SocketTestSupport;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static net.hasor.meteor.support.connector.protocol.UdpTestProtocol.packet;
import static net.hasor.meteor.support.connector.protocol.UdpTestProtocol.request;
import static net.hasor.meteor.support.connector.protocol.UdpTestProtocol.response;
import static org.junit.Assert.*;

/** 通过正式 SPI 和真实回环报文验证 UDP 连接器契约，不依赖 RPC 调度器。 */
public class UdpConnectorTest {
    @Test(timeout = 10000)
    public void peersWithTheSameRequestIdRemainIndependent() throws Exception {
        // 同一客户端建立两个 UDP peer，并使用相同请求号验证按通道隔离。
        ConnectorConfig serverConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        ConnectorConfig clientConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind(serverConfig.name()).get(2, TimeUnit.SECONDS);
            MetChannel first = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            MetChannel second = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertNotEquals(first.getChannelId(), second.getChannelId());

            // 接收端按源地址建立不同通道，不能把相同请求号合并为同一个 peer。
            first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
            Delivery firstRequest = serverInbox.take();
            second.sendData(request(1, "second")).get(2, TimeUnit.SECONDS);
            Delivery secondRequest = serverInbox.take();
            assertNotSame(firstRequest.channel, secondRequest.channel);
            assertNotEquals(firstRequest.channel.getRemote(), secondRequest.channel.getRemote());
            assertEquals(first.getLocal().getPort(), firstRequest.channel.getRemote().getPort());

            // 两个响应各自回到原通道，结果内容和通道身份均保持独立。
            firstRequest.channel.sendData(response(1, "one")).get(2, TimeUnit.SECONDS);
            Delivery firstResponse = clientInbox.take();
            ResponsePayload firstReply = (ResponsePayload) firstResponse.payload;
            assertSame(first, firstResponse.channel);
            assertEquals("one", firstReply.getReturnData());
            secondRequest.channel.sendData(response(1, "two")).get(2, TimeUnit.SECONDS);
            Delivery secondResponse = clientInbox.take();
            ResponsePayload secondReply = (ResponsePayload) secondResponse.payload;
            assertSame(second, secondResponse.channel);
            assertEquals("two", secondReply.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void acceptedPeerCanInitiateAReverseRequest() throws Exception {
        // 正向报文先建立服务端 peer，反向请求必须复用同一对 UDP 通道。
        ConnectorConfig serverConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        ConnectorConfig clientConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind(serverConfig.name()).get(2, TimeUnit.SECONDS);
            MetChannel outgoing = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "establish")).get(2, TimeUnit.SECONDS);
            Delivery accepted = serverInbox.take();

            // 已接受的 peer 主动请求客户端，客户端通过同一通道返回结果。
            accepted.channel.sendData(request(2, "reverse")).get(2, TimeUnit.SECONDS);
            Delivery reverse = clientInbox.take();
            RequestPayload reverseRequest = (RequestPayload) reverse.payload;
            assertSame(outgoing, reverse.channel);
            assertEquals("reverse", reverseRequest.getTargetMethod());
            reverse.channel.sendData(response(2, "back")).get(2, TimeUnit.SECONDS);
            Delivery returned = serverInbox.take();
            ResponsePayload reverseReply = (ResponsePayload) returned.payload;
            assertSame(accepted.channel, returned.channel);
            assertEquals("back", reverseReply.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void closeBindRejectsNewPeers() throws Exception {
        // 先建立一个 peer，使停止接纳后底层共享 UDP socket 仍被已有会话使用。
        ConnectorConfig serverConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        ConnectorConfig clientConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(serverConfig.name()).get(2, TimeUnit.SECONDS);
            MetChannel existing = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            existing.sendData(request(1, "establish")).get(2, TimeUnit.SECONDS);
            inbox.take();

            // 停止监听后，新源端口发来的报文不能创建新 peer 或投递消息。
            MetConnector connector = server.find(serverConfig.name());
            connector.closeBind();
            assertFalse(listen.isActive());
            assertTrue(connector.getListenList().isEmpty());
            MetChannel newcomer = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            newcomer.sendData(request(2, "rejected")).get(2, TimeUnit.SECONDS);
            assertNull(inbox.messages.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test(timeout = 10000)
    public void closeBindLeavesExistingPeerWritable() throws Exception {
        // 在关闭监听前完成一次报文交换，保证要保留的 peer 已被接纳。
        ConnectorConfig serverConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        ConnectorConfig clientConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind(serverConfig.name()).get(2, TimeUnit.SECONDS);
            MetChannel outgoing = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "establish")).get(2, TimeUnit.SECONDS);
            Delivery accepted = serverInbox.take();

            // 关闭监听只停止接纳，已有 peer 继续收发并保持原通道身份。
            server.find(serverConfig.name()).closeBind();
            assertFalse(listen.isActive());
            outgoing.sendData(request(3, "existing")).get(2, TimeUnit.SECONDS);
            Delivery existingRequest = serverInbox.take();
            assertSame(accepted.channel, existingRequest.channel);
            accepted.channel.sendData(response(3, "still writable")).get(2, TimeUnit.SECONDS);
            Delivery returned = clientInbox.take();
            ResponsePayload reply = (ResponsePayload) returned.payload;
            assertSame(outgoing, returned.channel);
            assertEquals("still writable", reply.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void closeBindPreventsRebinding() throws Exception {
        // 真实监听关闭后，同一连接器的接纳状态不能被再次 bind 重置。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config)) {
            server.init();
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            server.find(config.name()).closeBind();
            assertFalse(listen.isActive());

            // 再次绑定必须明确向调用者返回状态异常。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                server.bind(config.name()).get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test(timeout = 10000)
    public void closingOneAcceptedPeerDoesNotCloseSharedSocket() throws Exception {
        // 同一监听上建立两个 peer，让关闭操作只作用于第一个会话。
        ConnectorConfig serverConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        ConnectorConfig clientConfig = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind(serverConfig.name()).get(2, TimeUnit.SECONDS);
            MetChannel first = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            MetChannel second = client.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            first.sendData(request(1, "one")).get(2, TimeUnit.SECONDS);
            Delivery firstRequest = serverInbox.take();
            second.sendData(request(2, "two")).get(2, TimeUnit.SECONDS);
            Delivery secondRequest = serverInbox.take();

            // 关闭目标 peer 后，共享监听和另一个 peer 仍保持可用。
            firstRequest.channel.close().get(2, TimeUnit.SECONDS);
            assertFalse(firstRequest.channel.isActive());
            assertTrue(listen.isActive());
            second.sendData(request(3, "again")).get(2, TimeUnit.SECONDS);
            Delivery remaining = serverInbox.take();
            assertSame(secondRequest.channel, remaining.channel);
            secondRequest.channel.sendData(response(3, "alive")).get(2, TimeUnit.SECONDS);
            Delivery returned = clientInbox.take();
            ResponsePayload reply = (ResponsePayload) returned.payload;
            assertSame(second, returned.channel);
            assertEquals("alive", reply.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void jvmPeerPreservesIndependentDatagramBoundaries() throws Exception {
        // JVM 原始 UDP 对端连续发送不同长度报文，接收端不得串接或残留正文。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket peer = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(peer, target, packet(1, 1, "short"));
            Delivery first = inbox.take();
            send(peer, target, packet(1, 2, "a longer independent datagram"));
            Delivery second = inbox.take();

            // 两条独立消息复用同一 peer，正文仍与各自的报文边界一致。
            assertSame(first.channel, second.channel);
            RequestPayload firstRequest = (RequestPayload) first.payload;
            RequestPayload secondRequest = (RequestPayload) second.payload;
            assertEquals("short", firstRequest.getTargetMethod());
            assertEquals("a longer independent datagram", secondRequest.getTargetMethod());

            // 原始对端收到一个完整响应报文，头部和正文均保持精确字节内容。
            first.channel.sendData(response(1, "reply")).get(2, TimeUnit.SECONDS);
            peer.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            peer.receive(received);
            byte[] receivedBytes = Arrays.copyOf(received.getData(), received.getLength());
            assertArrayEquals(packet(2, 1, "reply"), receivedBytes);
        }
    }

    @Test(timeout = 10000)
    public void malformedFirstDatagramDoesNotInterruptHealthyPeer() throws Exception {
        // 正常 peer 已接纳，另一个源端口的首包直接发送残缺协议头。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket good = new DatagramSocket(); //
             DatagramSocket bad = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(good, target, packet(1, 1, "good"));
            Delivery healthy = inbox.take();

            // 新 peer 的坏首包不能投递成消息，也不能破坏已有 peer 的响应。
            send(bad, target, new byte[] { 1 });
            assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
            healthy.channel.sendData(response(1, "reply")).get(2, TimeUnit.SECONDS);
            good.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            good.receive(received);
            byte[] receivedBytes = Arrays.copyOf(received.getData(), received.getLength());
            assertArrayEquals(packet(2, 1, "reply"), receivedBytes);

            // 正常 peer 后续请求复用原通道，监听仍可使用。
            send(good, target, packet(1, 3, "healthy"));
            Delivery afterFailure = inbox.take();
            assertSame(healthy.channel, afterFailure.channel);
            assertTrue(listen.isActive());
        }
    }

    @Test(timeout = 10000)
    public void malformedPeerDoesNotInterruptHealthyPeer() throws Exception {
        // 先接纳正常与故障两个源端口，使坏报文关闭的目标可被直接观察。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket good = new DatagramSocket(); //
             DatagramSocket bad = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(good, target, packet(1, 1, "good"));
            Delivery healthy = inbox.take();
            send(bad, target, packet(1, 2, "bad"));
            Delivery malformed = inbox.take();

            // 残缺头部只关闭坏 peer，不能投递残缺消息或关闭共享监听。
            send(bad, target, new byte[] { 1 });
            awaitInactive(malformed.channel);
            assertFalse(malformed.channel.isActive());
            assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(listen.isActive());

            // 等故障生效后再收发，证明正常 peer 的会话和响应能力未受污染。
            healthy.channel.sendData(response(1, "reply")).get(2, TimeUnit.SECONDS);
            good.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            good.receive(received);
            byte[] receivedBytes = Arrays.copyOf(received.getData(), received.getLength());
            assertArrayEquals(packet(2, 1, "reply"), receivedBytes);
            send(good, target, packet(1, 3, "healthy"));
            Delivery afterFailure = inbox.take();
            assertSame(healthy.channel, afterFailure.channel);
        }
    }

    @Test(timeout = 10000)
    public void inboundLimitRejectsWholeDatagramWithoutTruncatingIt() throws Exception {
        // 用小于第二条报文的配置上限，先建立可观察的 UDP peer。
        ConnectorConfig config = SocketTestSupport.udpConfig(16, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket peer = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(peer, target, packet(1, 1, "ok"));
            MetChannel channel = inbox.take().channel;

            // 超限报文必须整包拒绝并关闭目标 peer，不能截断后投递成合法消息。
            byte[] oversized = packet(1, 2, "this datagram exceeds the configured limit");
            send(peer, target, oversized);
            awaitInactive(channel);
            assertFalse(channel.isActive());
            assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(listen.isActive());
        }
    }

    @Test(timeout = 10000)
    public void managerCloseDrainsSubmittedRepliesAndReleasesPort() throws Exception {
        // 建立一个入站 peer，关闭管理器时已有响应必须先完成物理发送。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket peer = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            MetListen listen = server.bind(config.name()).get(2, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
            send(peer, target, packet(1, 1, "hello"));
            Delivery request = inbox.take();

            // 中途关闭是本场景的动作；提交响应应排空，通道和监听均应关闭。
            Future<MetChannel> writing = request.channel.sendData(response(1, "drained"));
            server.close();
            assertSame(request.channel, writing.get(2, TimeUnit.SECONDS));
            assertFalse(request.channel.isActive());
            assertFalse(listen.isActive());
            peer.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            peer.receive(received);
            byte[] receivedBytes = Arrays.copyOf(received.getData(), received.getLength());
            assertArrayEquals(packet(2, 1, "drained"), receivedBytes);

            // 操作系统允许重绑原端口，证明共享 UDP socket 也已释放。
            try (DatagramSocket rebound = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test
    public void rejectsStreamProtocolBeforeCreatingManager() throws Exception {
        // TCP 测试协议不能挂在 UDP 传输上，配置准备阶段就应失败。
        ConnectorConfig config = SocketTestSupport.udpConfig(128, "rawprotocol");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            try (ConnectorManager ignored = SocketTestSupport.manager(config)) {
                fail("A TCP protocol must not be mounted on UDP");
            }
        });
        assertTrue(failure.getMessage().contains("requires transport tcp"));
    }

    @Test(timeout = 10000)
    public void rejectsZeroListenPortThroughBindFuture() throws Exception {
        // 地址模型允许表达零端口，但 UDP 监听必须明确拒绝这个配置。
        Map<String, String> options = Map.of("listenType", "udp", "workerThread", "1");
        ProtocolConfig protocol = new ProtocolConfig("udp", "udp", "testprotocol", options);
        InterAddress address = InterAddress.forBinding("udp", "127.0.0.1", 0, "default");
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();

            // Meteor 的绑定 future 上抛参数错误，不向 Neta 提交零端口监听。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                manager.bind(config.name()).get(2, TimeUnit.SECONDS);
            });
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof IllegalArgumentException);
            assertEquals("UDP listen port must be between 1 and 65535", cause.getMessage());
        }
    }

    @Test(timeout = 10000)
    public void rejectsZeroDatagramLimitBeforeBinding() throws Exception {
        // 零长度上限不是有效 UDP 网络资源参数。
        ConnectorConfig config = SocketTestSupport.udpConfig(0, "testprotocol");
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();

            // 创建网络连接器时失败，不能产生已监听的端点。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                manager.bind(config.name()).get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertNull(manager.find(config.name()));
        }
    }

    @Test(timeout = 10000)
    public void rejectsDatagramLimitAboveMaximumBeforeBinding() throws Exception {
        // 配置上限不能超过 UDP 可用的 65507 字节。
        ConnectorConfig config = SocketTestSupport.udpConfig(65508, "testprotocol");
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();

            // 超出协议边界的资源配置失败，不遗留可用连接器。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                manager.bind(config.name()).get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertNull(manager.find(config.name()));
        }
    }

    private static void send(DatagramSocket socket, InetSocketAddress remote, byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, remote));
    }

    private static void awaitInactive(MetChannel channel) throws InterruptedException, TimeoutException {
        // 每次检查真实通道状态，有界等待故障处理完成。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (channel.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (channel.isActive()) {
            throw new TimeoutException("UDP peer did not close after a malformed datagram");
        }
    }

    private static final class Delivery {
        private final MetChannel channel;
        private final Payload    payload;

        private Delivery(MetChannel channel, Payload payload) {
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class Inbox implements ConnectorSubscriber {
        private final BlockingQueue<Delivery> messages = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(MetChannel channel, long requestId, Payload payload) {
            this.messages.add(new Delivery(channel, payload));
        }

        private Delivery take() throws InterruptedException, TimeoutException {
            Delivery delivery = this.messages.poll(2, TimeUnit.SECONDS);
            if (delivery == null) {
                throw new TimeoutException("Expected a decoded UDP datagram");
            }
            return delivery;
        }
    }
}
