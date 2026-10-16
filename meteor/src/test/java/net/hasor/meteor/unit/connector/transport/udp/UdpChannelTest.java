/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.udp;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.udp.UdpChannelTestAccess;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import org.junit.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static org.junit.Assert.*;

/**
 * 通过真实 UDP 对端验证数据报 SPI，不依赖 RPC 运行时。
 */
public class UdpChannelTest {

    @Test(timeout = 10000)
    public void datagramsAndSeparateRepliesPreserveBoundaries() throws Exception {
        // 明确创建真实对端与传输资源，连接准备失败也会关闭传输。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 65507)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 一次写入对应一个数据报，连续回复保留各自边界。
                byte[] large = new byte[1200];
                Arrays.fill(large, (byte) 73);
                connection.write(large).get(2, TimeUnit.SECONDS);
                DatagramPacket packet = receive(peer);
                assertEquals(1200, packet.getLength());
                assertArrayEquals(large, Arrays.copyOf(packet.getData(), packet.getLength()));
                peer.send(new DatagramPacket(new byte[] { 1 }, 1, packet.getSocketAddress()));
                peer.send(new DatagramPacket(new byte[] { 2, 3, 4 }, 3, packet.getSocketAddress()));
                assertArrayEquals(new byte[] { 1 }, receiver.received.poll(2, TimeUnit.SECONDS));
                assertArrayEquals(new byte[] { 2, 3, 4 }, receiver.received.poll(2, TimeUnit.SECONDS));
                assertTrue(connection.isOpen());
            }
        }
    }

    @Test(timeout = 10000)
    public void outboundRepliesKeepTheInitiallySelectedMount() throws Exception {
        // 主动连接选择 A 挂载，同时准备一个会匹配回包内容的 B 探针。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            RoutedFactory receiver = new RoutedFactory("mount-a");
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 65507)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 回包内容明确匹配 B 探针，主动连接仍必须交付给创建连接时选定的 A 挂载。
                byte[] request = "AArequest".getBytes(StandardCharsets.US_ASCII);
                connection.write(request).get(2, TimeUnit.SECONDS);
                DatagramPacket packet = receive(peer);
                byte[] reply = "BBreply".getBytes(StandardCharsets.US_ASCII);
                peer.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));

                RoutedMessage received = receiver.received.poll(2, TimeUnit.SECONDS);
                assertNotNull(received);
                assertEquals("mount-a", received.mountId);
                assertArrayEquals(reply, received.message);
            }
        }
    }

    @Test(timeout = 10000)
    public void largeDatagramsAndRepliesPreserveBoundaries() throws Exception {
        // 多轮交替大小报文属于同一个边界保持负载。
        byte[] bytes = new byte[50000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }

        // IPv6 回环直接走本地 lo，避免 WSL 对 IPv4 回环的虚拟网卡转发。
        InetSocketAddress local = new InetSocketAddress("::1", 0);

        // 明确创建真实对端与传输资源，连接准备失败也会关闭传输。
        try (DatagramSocket peer = new DatagramSocket(local)) {
            InterAddress remote = new InterAddress("udp", "::1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 65507)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);
                for (int round = 0; round < 10; round++) {
                    // 双向校验大报文内容，每轮使用不同标记。
                    bytes[0] = (byte) round;
                    connection.write(bytes).get(2, TimeUnit.SECONDS);
                    DatagramPacket packet = receive(peer);
                    assertEquals(bytes.length, packet.getLength());
                    assertArrayEquals(bytes, Arrays.copyOf(packet.getData(), packet.getLength()));
                    peer.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                    assertArrayEquals(bytes, receiver.received.poll(2, TimeUnit.SECONDS));

                    // 大小报文交替，校验每次收发仍保留独立边界。
                    byte[] marker = new byte[] { (byte) round, 2, 3 };
                    peer.send(new DatagramPacket(marker, marker.length, packet.getSocketAddress()));
                    assertArrayEquals(marker, receiver.received.poll(2, TimeUnit.SECONDS));
                    connection.write(marker).get(2, TimeUnit.SECONDS);
                    DatagramPacket small = receive(peer);
                    assertArrayEquals(marker, Arrays.copyOf(small.getData(), small.getLength()));
                    assertTrue(connection.isOpen());
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void emptyDatagramIsRejected() throws Exception {
        // 创建真实数据报对端并通过 SocketRuntime 安装 UDP SPI。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 32)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 暂停发送队列，非法长度仍必须在入队前立即失败。
                DispatchBlocker blocker = new DispatchBlocker();
                connection.execute(blocker);
                try {
                    assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));

                    // 收到参数异常后才放行队列，不能让队列推进掩盖延迟校验。
                    Future<Void> writing = connection.write(new byte[0]);
                    var failure = assertThrows(ExecutionException.class, () -> writing.get(2, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof IllegalArgumentException);
                    assertTrue(connection.isOpen());
                    blocker.release.countDown();
                    blocker.completed.get(2, TimeUnit.SECONDS);
                } finally {
                    blocker.release.countDown();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void oversizedDatagramIsRejected() throws Exception {
        // 创建真实数据报对端并通过 SocketRuntime 安装 UDP SPI。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 32)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 暂停发送队列，非法长度仍必须在入队前立即失败。
                DispatchBlocker blocker = new DispatchBlocker();
                connection.execute(blocker);
                try {
                    assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));

                    // 收到参数异常后才放行队列，不能让队列推进掩盖延迟校验。
                    Future<Void> writing = connection.write(new byte[33]);
                    var failure = assertThrows(ExecutionException.class, () -> writing.get(2, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof IllegalArgumentException);
                    assertTrue(connection.isOpen());
                    blocker.release.countDown();
                    blocker.completed.get(2, TimeUnit.SECONDS);
                } finally {
                    blocker.release.countDown();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void queuedDatagramCopiesCallerBuffer() throws Exception {
        // 创建真实数据报对端并通过 SocketRuntime 安装 UDP SPI。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 32)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 用协调信号暂停执行队列，明确保证后面的数据报仍处于排队状态。
                DispatchBlocker blocker = new DispatchBlocker();
                connection.execute(blocker);
                try {
                    assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));

                    // 排队完成后修改调用方数组，实际发出的数据仍是提交时的快照。
                    byte[] bytes = new byte[] { 33, 44 };
                    Future<Void> writing = connection.write(bytes);
                    bytes[0] = 99;
                    blocker.release.countDown();
                    writing.get(2, TimeUnit.SECONDS);
                    DatagramPacket packet = receive(peer);
                    byte[] received = Arrays.copyOf(packet.getData(), packet.getLength());
                    assertArrayEquals(new byte[] { 33, 44 }, received);
                    blocker.completed.get(2, TimeUnit.SECONDS);
                } finally {
                    blocker.release.countDown();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void cancelledQueuedDatagramDoesNotReachPeer() throws Exception {
        // 创建真实数据报对端并通过 SocketRuntime 安装 UDP SPI。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 32)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 用协调信号暂停执行队列，明确保证后面的数据报仍处于排队状态。
                DispatchBlocker blocker = new DispatchBlocker();
                connection.execute(blocker);
                try {
                    assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));

                    // 在首尾两个报文之间取消一个报文，后续报文不能被取消操作阻塞。
                    Future<Void> first = connection.write(new byte[] { 11 });
                    Future<Void> cancelled = connection.write(new byte[] { 22 });
                    assertTrue(cancelled.cancel());
                    Future<Void> last = connection.write(new byte[] { 33 });
                    Future<Void> closed = connection.drainAndClose();
                    assertFalse(closed.isDone());
                    blocker.release.countDown();
                    first.get(2, TimeUnit.SECONDS);
                    last.get(2, TimeUnit.SECONDS);
                    DatagramPacket one = receive(peer);
                    DatagramPacket two = receive(peer);
                    assertArrayEquals(new byte[] { 11 }, Arrays.copyOf(one.getData(), one.getLength()));
                    assertArrayEquals(new byte[] { 33 }, Arrays.copyOf(two.getData(), two.getLength()));
                    assertTrue(cancelled.isCancelled());

                    // 取消的排队写也必须归还计数，不能使排空永远等待。
                    closed.get(2, TimeUnit.SECONDS);
                    assertSame(closed, connection.close());
                    assertFalse(connection.isOpen());
                    blocker.completed.get(2, TimeUnit.SECONDS);
                } finally {
                    blocker.release.countDown();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void drainClosesAfterQueuedWritesWithoutPeerReply() throws Exception {
        // 创建真实数据报对端并通过 SocketRuntime 安装 UDP SPI。
        try (DatagramSocket peer = new DatagramSocket()) {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", peer.getLocalPort(), "default");
            ConnectorConfig config = this.config(remote);
            Receiver receiver = new Receiver();
            ClassLoader loader = this.getClass().getClassLoader();
            TransportConfig transportConfig = transport(config);
            try (TestSocketRuntime transport = new TestSocketRuntime( //
                    transportConfig, loader, this.socketConfig())) {
                transport.connect(remote, stack -> receiver.attach(stack, remote, transport, 32)) //
                        .get(2, TimeUnit.SECONDS);
                NetworkChannel<byte[]> connection = receiver.connected.get(2, TimeUnit.SECONDS);
                peer.setSoTimeout(2000);

                // 用协调信号暂停执行队列，明确保证后面的数据报仍处于排队状态。
                DispatchBlocker blocker = new DispatchBlocker();
                connection.execute(blocker);
                try {
                    assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));

                    // 排空等待已有写入，不接受新写入，也不等待对端回复。
                    Future<Void> first = connection.write(new byte[] { 11 });
                    Future<Void> last = connection.write(new byte[] { 33, 44 });
                    Future<Void> closed = connection.drainAndClose();
                    assertFalse(closed.isDone());
                    Future<Void> rejected = connection.write(new byte[] { 55 });
                    var failure = assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof IllegalStateException);
                    blocker.release.countDown();
                    first.get(2, TimeUnit.SECONDS);
                    last.get(2, TimeUnit.SECONDS);
                    DatagramPacket one = receive(peer);
                    DatagramPacket two = receive(peer);
                    assertArrayEquals(new byte[] { 11 }, Arrays.copyOf(one.getData(), one.getLength()));
                    assertArrayEquals(new byte[] { 33, 44 }, Arrays.copyOf(two.getData(), two.getLength()));
                    closed.get(2, TimeUnit.SECONDS);
                    assertSame(closed, connection.close());
                    assertFalse(connection.isOpen());
                    blocker.completed.get(2, TimeUnit.SECONDS);
                } finally {
                    blocker.release.countDown();
                }
            }
        }
    }

    private static DatagramPacket receive(DatagramSocket peer) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[65536], 65536);
        peer.receive(packet);
        return packet;
    }

    private ConnectorConfig config(InterAddress remote) {
        Map<String, String> options = Collections.emptyMap();
        ProtocolConfig mount = ProtocolTestAccess.config("raw-udp", "udp", "udp", options);
        return new ConnectorConfig("raw-udp", remote, options, List.of(mount), true);
    }

    private UdpSoConfig socketConfig() {
        UdpSoConfig config = new UdpSoConfig();
        config.setRcvPacketSize(65536);
        return config;
    }

    private static final class Receiver implements ChannelListenerFactory<byte[]> {
        private final BasicFuture<NetworkChannel<byte[]>> connected = new BasicFuture<>();
        private final BlockingQueue<byte[]>               received  = new LinkedBlockingQueue<>();

        private void attach(ProtoBuildContext stack, InterAddress remote, TestSocketRuntime transport, int maximum) {
            try {
                RouteTable<byte[]> routeTable = RouteTestAccess.activate(Collections.emptyMap(), null);
                UdpChannelTestAccess.attach(stack, remote, transport.nextExecutor(), maximum, //
                        routeTable, null, this);
            } catch (Exception failure) {
                this.connected.failed(failure);
                throw new IllegalStateException(failure);
            }
        }

        public ChannelListener<byte[]> createListener(NetworkChannel<byte[]> channel) {
            return new ChannelListener<byte[]>() {
                public void connected() {
                    Receiver.this.connected.completed(channel);
                }

                public void receive(byte[] datagram) {
                    Receiver.this.received.add(datagram);
                }

                public void closed(Throwable cause) {
                    Receiver.this.connected.failed(cause);
                }
            };
        }
    }

    private static final class RoutedFactory implements ChannelListenerFactory<byte[]> {
        private final String                               initialMountId;
        private final Map<String, RouteDefinition<byte[]>> routes;
        private final BasicFuture<NetworkChannel<byte[]>>  connected = new BasicFuture<>();
        private final BlockingQueue<RoutedMessage>         received  = new LinkedBlockingQueue<>();

        private RoutedFactory(String initialMountId) {
            this.initialMountId = initialMountId;
            Map<String, RouteDefinition<byte[]>> routes = new LinkedHashMap<>();
            RouteDefinition<byte[]> first = RouteTestAccess.definition(//
                    Collections.emptyMap(), null, message -> probe("AA", message));
            RouteDefinition<byte[]> second = RouteTestAccess.definition(//
                    Collections.emptyMap(), null, message -> probe("BB", message));
            routes.put("mount-a", first);
            routes.put("mount-b", second);
            this.routes = Collections.unmodifiableMap(routes);
        }

        private void attach(ProtoBuildContext stack, InterAddress remote, TestSocketRuntime transport, int maximum) {
            try {
                RouteTable<byte[]> routeTable = RouteTestAccess.activate(this.routes, null);
                UdpChannelTestAccess.attach(stack, remote, transport.nextExecutor(), maximum, //
                        routeTable, this.initialMountId, this);
            } catch (Exception failure) {
                this.connected.failed(failure);
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public ChannelListener<byte[]> createListener(NetworkChannel<byte[]> channel) {
            return new RouteReceiver<byte[]>() {
                @Override
                public void connected() {
                    RoutedFactory.this.connected.completed(channel);
                }

                @Override
                public void receive(String mountId, byte[] message) {
                    RoutedFactory.this.received.add(new RoutedMessage(mountId, message));
                }

                @Override
                public void receive(byte[] message) {
                    throw new IllegalStateException("Protocol route is missing");
                }

                @Override
                public void closed(Throwable cause) {
                    RoutedFactory.this.connected.failed(cause);
                }
            };
        }

        private static RouteProbeResult probe(String marker, byte[] message) {
            if (message.length < marker.length()) {
                return RouteProbeResult.NEED_MORE;
            }
            String prefix = new String(message, 0, marker.length(), StandardCharsets.US_ASCII);
            return StringUtils.equals(marker, prefix) ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
        }
    }

    private record RoutedMessage(String mountId, byte[] message) {
    }

    private static final class DispatchBlocker implements Runnable {
        private final CountDownLatch    entered   = new CountDownLatch(1);
        private final CountDownLatch    release   = new CountDownLatch(1);
        private final BasicFuture<Void> completed = new BasicFuture<>();

        public void run() {
            this.entered.countDown();
            try {
                // 超时和中断显式传回测试线程，不能静默滞留执行队列。
                if (!this.release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("UDP dispatch was not released");
                }
                this.completed.completed(null);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                this.completed.failed(failure);
            } catch (Exception failure) {
                this.completed.failed(failure);
            }
        }
    }
}
