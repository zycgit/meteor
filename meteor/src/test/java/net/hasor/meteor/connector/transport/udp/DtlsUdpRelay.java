/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only UDP relay that drops the first complete DTLS ClientHello. */
final class DtlsUdpRelay implements AutoCloseable {
    private static final int DATAGRAM_CAPACITY = 65535;
    private static final int RECORD_HEADER     = 13;
    private static final int HANDSHAKE_HEADER  = 12;

    private final    DatagramSocket    clientSocket;
    private final    DatagramSocket    serverSocket;
    private final    InetSocketAddress serverAddress;
    private final    Thread            clientRelay;
    private final    Thread            serverRelay;
    private final    CountDownLatch    retransmission      = new CountDownLatch(1);
    private final    AtomicInteger     clientHelloAttempts = new AtomicInteger();
    private volatile boolean           running             = true;
    private volatile SocketAddress     clientAddress;
    private volatile ClientHelloKey    droppedClientHello;
    private volatile Throwable         failure;

    private DtlsUdpRelay(InetSocketAddress serverAddress) throws SocketException {
        InetSocketAddress local = new InetSocketAddress("127.0.0.1", 0);
        this.clientSocket = new DatagramSocket(local);
        this.serverSocket = new DatagramSocket(local);
        this.serverAddress = serverAddress;
        this.clientRelay = this.newThread("Meteor-DTLS-client-relay", this::relayClientDatagrams);
        this.serverRelay = this.newThread("Meteor-DTLS-server-relay", this::relayServerDatagrams);
        this.clientRelay.start();
        this.serverRelay.start();
    }

    static DtlsUdpRelay start(InetSocketAddress serverAddress) throws SocketException {
        return new DtlsUdpRelay(serverAddress);
    }

    int port() {
        return this.clientSocket.getLocalPort();
    }

    int clientHelloAttempts() {
        return this.clientHelloAttempts.get();
    }

    boolean awaitRetransmission(long timeout, TimeUnit unit) throws InterruptedException {
        return this.retransmission.await(timeout, unit);
    }

    void assertHealthy() {
        if (this.failure != null) {
            throw new AssertionError("DTLS UDP relay failed", this.failure);
        }
    }

    private Thread newThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private void relayClientDatagrams() {
        byte[] buffer = new byte[DATAGRAM_CAPACITY];
        while (this.running) {
            try {
                // Receive from the Meteor client and retain its address for reverse traffic.
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.clientSocket.receive(packet);
                this.clientAddress = packet.getSocketAddress();
                byte[] datagram = packet.getData();
                int offset = packet.getOffset();
                int length = packet.getLength();

                // Drop only the first initial ClientHello; a matching next flight proves retransmission.
                ClientHelloKey key = clientHello(datagram, offset, length);
                if (key != null && this.dropOrObserveRetransmission(key)) {
                    continue;
                }

                DatagramPacket forwarded = new DatagramPacket(datagram, offset, length, this.serverAddress);
                this.serverSocket.send(forwarded);
            } catch (Throwable failure) {
                this.handleFailure(failure);
            }
        }
    }

    private boolean dropOrObserveRetransmission(ClientHelloKey key) {
        ClientHelloKey dropped = this.droppedClientHello;
        if (dropped == null) {
            this.droppedClientHello = key;
            this.clientHelloAttempts.incrementAndGet();
            return true;
        }
        if (dropped.matches(key)) {
            this.clientHelloAttempts.incrementAndGet();
            this.retransmission.countDown();
        }
        return false;
    }

    private void relayServerDatagrams() {
        byte[] buffer = new byte[DATAGRAM_CAPACITY];
        while (this.running) {
            try {
                // Replies leave through the advertised relay socket so a connected UDP client accepts them.
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.serverSocket.receive(packet);
                SocketAddress client = this.clientAddress;
                if (client != null) {
                    DatagramPacket forwarded = new DatagramPacket(packet.getData(), packet.getOffset(), //
                            packet.getLength(), client);
                    this.clientSocket.send(forwarded);
                }
            } catch (Throwable failure) {
                this.handleFailure(failure);
            }
        }
    }

    private void handleFailure(Throwable failure) {
        if (!this.running) {
            return;
        }
        this.failure = failure;
        this.running = false;
        this.clientSocket.close();
        this.serverSocket.close();
    }

    private static ClientHelloKey clientHello(byte[] bytes, int offset, int length) {
        int end = offset + length;
        int record = offset;
        while (record + RECORD_HEADER <= end) {
            int contentType = unsigned(bytes[record]);
            int recordLength = unsignedShort(bytes, record + 11);
            int content = record + RECORD_HEADER;
            int recordEnd = content + recordLength;
            if (recordEnd > end) {
                return null;
            }

            ClientHelloKey key = clientHelloInRecord(bytes, contentType, content, recordEnd);
            if (key != null) {
                return key;
            }
            record = recordEnd;
        }
        return null;
    }

    private static ClientHelloKey clientHelloInRecord(byte[] bytes, int contentType, int offset, int end) {
        if (contentType != 22) {
            return null;
        }

        int handshake = offset;
        while (handshake + HANDSHAKE_HEADER <= end) {
            int messageType = unsigned(bytes[handshake]);
            int messageSequence = unsignedShort(bytes, handshake + 4);
            int fragmentOffset = unsignedMedium(bytes, handshake + 6);
            int fragmentLength = unsignedMedium(bytes, handshake + 9);
            int next = handshake + HANDSHAKE_HEADER + fragmentLength;
            if (next > end) {
                return null;
            }
            if (messageType == 1 && messageSequence == 0 && fragmentOffset == 0) {
                return new ClientHelloKey(contentType, messageType, messageSequence, fragmentOffset);
            }
            handshake = next;
        }
        return null;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static int unsignedMedium(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) << 16 | unsigned(bytes[offset + 1]) << 8 | unsigned(bytes[offset + 2]);
    }

    @Override
    public void close() {
        this.running = false;
        this.clientSocket.close();
        this.serverSocket.close();
        this.join(this.clientRelay);
        this.join(this.serverRelay);
    }

    private void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ClientHelloKey {
        private final int contentType;
        private final int messageType;
        private final int messageSequence;
        private final int fragmentOffset;

        private ClientHelloKey(int contentType, int messageType, int messageSequence, int fragmentOffset) {
            this.contentType = contentType;
            this.messageType = messageType;
            this.messageSequence = messageSequence;
            this.fragmentOffset = fragmentOffset;
        }

        private boolean matches(ClientHelloKey other) {
            return this.contentType == other.contentType && this.messageType == other.messageType && //
                    this.messageSequence == other.messageSequence && this.fragmentOffset == other.fragmentOffset;
        }
    }
}
