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
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.support.connector.protocol.SocketTestSupport;
import org.junit.Test;
import static net.hasor.meteor.support.connector.protocol.UdpTestProtocol.*;
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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            MetChannel first = client.connect(listenAddress).get(2, TimeUnit.SECONDS);
            MetChannel second = client.connect(listenAddress).get(2, TimeUnit.SECONDS);
            assertNotSame(first, second);

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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            MetChannel outgoing = client.connect(listenAddress).get(2, TimeUnit.SECONDS);
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
    public void stoppedInboundRestartsTheSameUdpListenerAndAcceptsAFreshPeer() throws Exception {
        // 通过正式管理器开放 UDP 监听，并用原始数据报建立一个入站 peer。
        ConnectorConfig config = SocketTestSupport.udpConfig(65507, "testprotocol");
        try (ConnectorManager server = SocketTestSupport.manager(config); //
             DatagramSocket firstPeer = new DatagramSocket(); //
             DatagramSocket secondPeer = new DatagramSocket()) {
            server.init();
            Inbox inbox = new Inbox();
            server.subscribe(inbox);
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            InterAddress bound = listenAddress;
            InetSocketAddress target = new InetSocketAddress(bound.getHost(), bound.getPort());
            send(firstPeer, target, packet(1, 1, "first"));
            Delivery first = inbox.take();

            // 离线停止共享监听并关闭旧 peer，停止期间不再接收数据报。
            server.stopInbound();
            assertNull(server.bindAddress("udp"));
            send(secondPeer, target, packet(1, 2, "stopped"));
            assertNull(inbox.messages.poll(200, TimeUnit.MILLISECONDS));

            // 重新上线复用原监听和端口，新来源可以建立全新的 peer。
            server.startInbound();
            assertNotNull(server.bindAddress("udp"));
            assertEquals(bound, server.bindAddress("udp"));
            send(secondPeer, target, packet(1, 3, "restarted"));
            Delivery restarted = inbox.take();
            assertNotSame(first.channel, restarted.channel);
            RequestPayload restartedRequest = (RequestPayload) restarted.payload;
            assertEquals("restarted", restartedRequest.getTargetMethod());
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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listenAddress.getPort());
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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listenAddress.getPort());
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
            assertNotNull(server.bindAddress("udp"));
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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listenAddress.getPort());
            send(good, target, packet(1, 1, "good"));
            Delivery healthy = inbox.take();
            send(bad, target, packet(1, 2, "bad"));
            Delivery malformed = inbox.take();

            // 残缺头部只关闭坏 peer，不能投递残缺消息或关闭共享监听。
            send(bad, target, new byte[] { 1 });
            assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
            assertNotNull(server.bindAddress("udp"));

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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listenAddress.getPort());
            send(peer, target, packet(1, 1, "ok"));
            MetChannel channel = inbox.take().channel;

            // 超限报文必须整包拒绝并关闭目标 peer，不能截断后投递成合法消息。
            byte[] oversized = packet(1, 2, "this datagram exceeds the configured limit");
            send(peer, target, oversized);
            assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
            assertNotNull(server.bindAddress("udp"));
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
            server.startInbound();
            InterAddress listenAddress = server.bindAddress("udp");
            int port = listenAddress.getPort();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
            send(peer, target, packet(1, 1, "hello"));
            Delivery request = inbox.take();

            // 中途关闭是本场景的动作；提交响应应排空，通道和监听均应关闭。
            Future<MetChannel> writing = request.channel.sendData(response(1, "drained"));
            server.close();
            assertSame(request.channel, writing.get(2, TimeUnit.SECONDS));
            assertNull(server.bindAddress("udp"));
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
    public void rejectsStreamProtocolDuringManagerInitialization() throws Exception {
        // TCP 测试协议不能挂在 UDP 传输上，初始化编译启动计划时应失败。
        ConnectorConfig config = SocketTestSupport.udpConfig(128, "rawprotocol");
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("requires transport tcp"));
        }
    }

    @Test(timeout = 10000)
    public void rejectsZeroListenPortWhenStartingInbound() throws Exception {
        // 地址模型允许表达零端口，但 UDP 监听必须明确拒绝这个配置。
        Map<String, String> options = Map.of("workerThread", "1");
        ProtocolConfig protocol = ProtocolTestAccess.config("udp", "udp", "testprotocol", options);
        InterAddress address = InterAddress.forBinding("udp", "127.0.0.1", 0, "default");
        ConnectorConfig config = new ConnectorConfig("udp", address, options, List.of(protocol), true);
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();

            // 启动入站时抛出参数错误，不向 Neta 提交零端口监听。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                manager.startInbound();
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
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                manager.startInbound();
            });
            assertEquals("maxDatagramSize must be positive", failure.getMessage());
            assertNull(manager.bindAddress("udp"));
        }
    }

    @Test(timeout = 10000)
    public void rejectsDatagramLimitAboveMaximumBeforeBinding() throws Exception {
        // 配置上限不能超过 UDP 可用的 65507 字节。
        ConnectorConfig config = SocketTestSupport.udpConfig(65508, "testprotocol");
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();

            // 超出协议边界的资源配置失败，不遗留可用连接器。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                manager.startInbound();
            });
            assertEquals("maxDatagramSize must be between 1 and 65507", failure.getMessage());
            assertNull(manager.bindAddress("udp"));
        }
    }

    private static void send(DatagramSocket socket, InetSocketAddress remote, byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, remote));
    }

    private record Delivery(MetChannel channel, Payload payload) {
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
