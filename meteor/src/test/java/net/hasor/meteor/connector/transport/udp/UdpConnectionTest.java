/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Raw datagram extensions do not depend on MetChannel, Payload or the RPC runtime.
 */
public class UdpConnectionTest {

    @Test(timeout = 10000)
    public void datagramsAndSeparateRepliesPreserveBoundaries() throws Exception {
        try (DatagramSocket peer = new DatagramSocket(); Endpoint endpoint = new Endpoint("127.0.0.1", peer.getLocalPort(), 65507)) {
            peer.setSoTimeout(2000);
            byte[] large = new byte[1200];
            Arrays.fill(large, (byte) 73);
            endpoint.connection.write(large).get(2, TimeUnit.SECONDS);
            DatagramPacket packet = receive(peer);
            assertEquals(1200, packet.getLength());
            assertArrayEquals(large, Arrays.copyOf(packet.getData(), packet.getLength()));
            peer.send(new DatagramPacket(new byte[] { 1 }, 1, packet.getSocketAddress()));
            peer.send(new DatagramPacket(new byte[] { 2, 3, 4 }, 3, packet.getSocketAddress()));
            assertArrayEquals(new byte[] { 1 }, endpoint.received.poll(2, TimeUnit.SECONDS));
            assertArrayEquals(new byte[] { 2, 3, 4 }, endpoint.received.poll(2, TimeUnit.SECONDS));
            assertTrue(endpoint.connection.isOpen());
        }
    }

    @Test(timeout = 10000)
    public void largeDatagramsAndRepliesPreserveBoundaries() throws Exception {
        byte[] bytes = new byte[50000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }

        // IPv6 回环直接走本地 lo，避免 WSL 对 IPv4 回环的虚拟网卡转发。
        InetSocketAddress local = new InetSocketAddress("::1", 0);
        try (DatagramSocket peer = new DatagramSocket(local); Endpoint endpoint = new Endpoint("::1", peer.getLocalPort(), 65507)) {
            peer.setSoTimeout(2000);
            for (int round = 0; round < 10; round++) {
                // 双向校验大报文内容，每轮使用不同标记。
                bytes[0] = (byte) round;
                endpoint.connection.write(bytes).get(2, TimeUnit.SECONDS);
                DatagramPacket packet = receive(peer);
                assertEquals(bytes.length, packet.getLength());
                assertArrayEquals(bytes, Arrays.copyOf(packet.getData(), packet.getLength()));
                peer.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                assertArrayEquals(bytes, endpoint.received.poll(2, TimeUnit.SECONDS));

                // 大小报文交替，校验每次收发仍保留独立边界。
                byte[] marker = new byte[] { (byte) round, 2, 3 };
                peer.send(new DatagramPacket(marker, marker.length, packet.getSocketAddress()));
                assertArrayEquals(marker, endpoint.received.poll(2, TimeUnit.SECONDS));
                endpoint.connection.write(marker).get(2, TimeUnit.SECONDS);
                DatagramPacket small = receive(peer);
                assertArrayEquals(marker, Arrays.copyOf(small.getData(), small.getLength()));
                assertTrue(endpoint.connection.isOpen());
            }
        }
    }

    @Test(timeout = 10000)
    public void copiesAndCancelsQueuedDatagramsThenDrainsWithoutPeerReply() throws Exception {
        try (DatagramSocket peer = new DatagramSocket(); Endpoint endpoint = new Endpoint("127.0.0.1", peer.getLocalPort(), 32)) {
            peer.setSoTimeout(2000);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            endpoint.connection.execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(failure(endpoint.connection.write(new byte[0])) instanceof IllegalArgumentException);
                assertTrue(failure(endpoint.connection.write(new byte[33])) instanceof IllegalArgumentException);
                Future<Void> first = endpoint.connection.write(new byte[] { 11 });
                Future<Void> cancelled = endpoint.connection.write(new byte[] { 22 });
                assertTrue(cancelled.cancel());
                byte[] last = new byte[] { 33, 44 };
                Future<Void> finalWrite = endpoint.connection.write(last);
                last[0] = 99;
                Future<Void> closed = endpoint.connection.drainAndClose();
                assertFalse(closed.isDone());
                assertTrue(failure(endpoint.connection.write(new byte[] { 55 })) instanceof IllegalStateException);
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
                finalWrite.get(2, TimeUnit.SECONDS);
                DatagramPacket one = receive(peer);
                DatagramPacket two = receive(peer);
                assertArrayEquals(new byte[] { 11 }, Arrays.copyOf(one.getData(), one.getLength()));
                assertArrayEquals(new byte[] { 33, 44 }, Arrays.copyOf(two.getData(), two.getLength()));
                closed.get(2, TimeUnit.SECONDS);
                assertSame(closed, endpoint.connection.close());
                assertFalse(endpoint.connection.isOpen());
            } finally {
                release.countDown();
            }
        }
    }

    private static DatagramPacket receive(DatagramSocket peer) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[65536], 65536);
        peer.receive(packet);
        return packet;
    }

    private static Throwable failure(Future<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected failed write");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static final class Endpoint implements AutoCloseable {

        private final SocketTransport transport;

        private final UdpConnection connection;

        private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();

        private Endpoint(String host, int port, int maximum) throws Exception {
            InterAddress remote = new InterAddress("udp", host, port, "default");
            Map<String, String> options = Collections.singletonMap("listenType", "udp");
            String scheme = remote.getSchema();
            ProtocolConfig mount = new ProtocolConfig("raw-udp", scheme, scheme, options);
            ConnectorConfig config = new ConnectorConfig("raw-udp", remote, options, Collections.singletonList(mount), true);
            UdpSoConfig socket = new UdpSoConfig();
            socket.setRcvPacketSize(65536);
            this.transport = new SocketTransport(config, getClass().getClassLoader(), socket);
            BasicFuture<Void> ready = new BasicFuture<>();
            AtomicReference<UdpConnection> connection = new AtomicReference<>();
            try {
                Future<NetChannel> connecting = this.transport.connect(remote, stack -> {
                    try {
                        connection.set(UdpConnection.attach(stack, remote, this.transport.nextExecutor(), maximum, channel -> new ChannelListener<byte[]>() {

                            public Future<Void> ready() {
                                return ready;
                            }

                            public void connected() {
                                ready.completed(null);
                            }

                            public void receive(byte[] datagram) {
                                Endpoint.this.received.add(datagram);
                            }

                            public void closed(Throwable cause) {
                                ready.failed(cause);
                            }
                        }));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                connecting.get(2, TimeUnit.SECONDS);
                ready.get(2, TimeUnit.SECONDS);
                this.connection = connection.get();
            } catch (Exception failure) {
                this.transport.close();
                throw failure;
            }
        }

        public void close() {
            try {
                this.connection.close().get(2, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                this.transport.close();
            }
        }
    }
}
