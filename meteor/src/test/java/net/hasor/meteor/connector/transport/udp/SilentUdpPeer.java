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
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only UDP peer that continuously receives datagrams without replying. */
final class SilentUdpPeer implements AutoCloseable {
    private static final int DATAGRAM_CAPACITY = 65535;

    private final    DatagramSocket socket;
    private final    Thread         receiver;
    private final    CountDownLatch received = new CountDownLatch(1);
    private volatile boolean        running  = true;
    private volatile Throwable      failure;

    private SilentUdpPeer() throws SocketException {
        InetSocketAddress local = new InetSocketAddress("127.0.0.1", 0);
        this.socket = new DatagramSocket(local);
        this.receiver = new Thread(this::receiveDatagrams, "Meteor-DTLS-silent-peer");
        this.receiver.setDaemon(true);
        this.receiver.start();
    }

    static SilentUdpPeer start() throws SocketException {
        return new SilentUdpPeer();
    }

    int port() {
        return this.socket.getLocalPort();
    }

    boolean awaitDatagram(long timeout, TimeUnit unit) throws InterruptedException {
        return this.received.await(timeout, unit);
    }

    void assertHealthy() {
        if (this.failure != null) {
            throw new AssertionError("Silent UDP peer failed", this.failure);
        }
    }

    private void receiveDatagrams() {
        byte[] buffer = new byte[DATAGRAM_CAPACITY];
        while (this.running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(packet);
                this.received.countDown();
            } catch (Throwable failure) {
                if (this.running) {
                    this.failure = failure;
                    this.running = false;
                    this.socket.close();
                }
            }
        }
    }

    @Override
    public void close() {
        this.running = false;
        this.socket.close();
        try {
            this.receiver.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
