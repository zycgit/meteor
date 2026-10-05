/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.udp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.ChannelListener;
import net.hasor.rsf.connector.transport.SocketTransport;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Raw datagram extensions do not depend on RsfChannel, Payload or the RPC runtime.
 */
public class UdpConnectionTest {

    @Test(timeout = 10000)
    public void datagramsAndSeparateRepliesPreserveBoundaries() throws Exception {
        try (DatagramSocket peer = new DatagramSocket(); Endpoint endpoint = new Endpoint(peer.getLocalPort(), 65507)) {
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
    public void largeDatagramPreservesBoundaryWhenNetworkSupportsIt() throws Exception {
        byte[] bytes = new byte[50000];
        Arrays.fill(bytes, (byte) 73);
        try (DatagramSocket probe = new DatagramSocket(); DatagramSocket sender = new DatagramSocket()) {
            probe.setSoTimeout(300);
            sender.send(new DatagramPacket(bytes, bytes.length, new InetSocketAddress("127.0.0.1", probe.getLocalPort())));
            try {
                receive(probe);
            } catch (SocketTimeoutException unavailable) {
                Assume.assumeNoException("Environment drops large UDP datagrams independently of Neta", unavailable);
            }
        }
        try (DatagramSocket peer = new DatagramSocket(); Endpoint endpoint = new Endpoint(peer.getLocalPort(), 65507)) {
            peer.setSoTimeout(2000);
            endpoint.connection.write(bytes).get(2, TimeUnit.SECONDS);
            DatagramPacket packet = receive(peer);
            assertEquals(bytes.length, packet.getLength());
            assertArrayEquals(bytes, Arrays.copyOf(packet.getData(), packet.getLength()));
        }
    }

    @Test(timeout = 10000)
    public void copiesAndCancelsQueuedDatagramsThenDrainsWithoutPeerReply() throws Exception {
        try (DatagramSocket peer = new DatagramSocket(); Endpoint endpoint = new Endpoint(peer.getLocalPort(), 32)) {
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

        private Endpoint(int port, int maximum) throws Exception {
            InterAddress remote = new InterAddress("udp", "127.0.0.1", port, "default");
            ConnectorConfig config = new ConnectorConfig("raw-udp", remote, Collections.singletonMap("listenType", "udp"));
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
