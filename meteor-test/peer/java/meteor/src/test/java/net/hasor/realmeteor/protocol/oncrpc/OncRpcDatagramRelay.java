/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** 保持 UDP 来源地址的故障代理；只丢弃、转发和重放原始报文，不解析协议。 */
final class OncRpcDatagramRelay implements AutoCloseable {
    enum Fault {
        NONE,
        DROP_FIRST_REQUEST,
        DROP_FIRST_REPLY,
        DROP_ALL_REQUESTS
    }

    private final    InetSocketAddress target;
    private final    Fault             fault;
    private final    DatagramSocket    socket;
    private final    Thread            worker;
    private final    Object            observation = new Object();
    private volatile boolean           closed;
    private          SocketAddress     client;
    private          byte[]            firstRequest;
    private          byte[]            firstReply;
    private          byte[]            lastReply;
    private          int               clientRequests;
    private          int               serverReplies;
    private          Throwable         failure;

    OncRpcDatagramRelay(InetSocketAddress target, Fault fault) throws IOException {
        this.target = target;
        this.fault = fault;
        this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.worker = new Thread(this::receive, "oncrpc-datagram-relay");
        this.worker.start();
    }

    int port() {
        return this.socket.getLocalPort();
    }

    int clientRequests() {
        synchronized (this.observation) {
            return this.clientRequests;
        }
    }

    byte[] firstReply() {
        synchronized (this.observation) {
            return this.firstReply.clone();
        }
    }

    byte[] lastReply() {
        synchronized (this.observation) {
            return this.lastReply.clone();
        }
    }

    void replayFirstRequest() throws IOException {
        byte[] request;
        synchronized (this.observation) {
            if (this.firstRequest == null) {
                throw new IllegalStateException("No client datagram has been observed");
            }
            request = this.firstRequest;
        }
        this.socket.send(new DatagramPacket(request, request.length, this.target));
    }

    boolean awaitServerReplies(int expected, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this.observation) {
            while (this.serverReplies < expected) {
                this.requireHealthy();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this.observation, remaining);
            }
            return true;
        }
    }

    private void receive() {
        byte[] buffer = new byte[65535];
        try {
            while (!this.closed) {
                // 单个 socket 同时面对客户端和服务端，使重放仍属于原来的 UDP 对端。
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(packet);
                int start = packet.getOffset();
                int end = start + packet.getLength();
                byte[] bytes = Arrays.copyOfRange(packet.getData(), start, end);
                if (this.target.equals(packet.getSocketAddress())) {
                    this.receiveReply(bytes);
                } else {
                    this.receiveRequest(packet.getSocketAddress(), bytes);
                }
            }
        } catch (Throwable error) {
            synchronized (this.observation) {
                if (!this.closed) {
                    this.failure = error;
                }
                this.observation.notifyAll();
            }
        }
    }

    private void receiveRequest(SocketAddress source, byte[] bytes) throws IOException {
        boolean discard;
        synchronized (this.observation) {
            this.client = source;
            this.clientRequests++;
            if (this.firstRequest == null) {
                this.firstRequest = bytes;
            }
            boolean firstLost = this.fault == Fault.DROP_FIRST_REQUEST && this.clientRequests == 1;
            discard = firstLost || this.fault == Fault.DROP_ALL_REQUESTS;
            this.observation.notifyAll();
        }

        // 故障只影响指定方向，其他报文原样透传。
        if (!discard) {
            this.socket.send(new DatagramPacket(bytes, bytes.length, this.target));
        }
    }

    private void receiveReply(byte[] bytes) throws IOException {
        SocketAddress destination;
        boolean discard;
        synchronized (this.observation) {
            this.serverReplies++;
            if (this.firstReply == null) {
                this.firstReply = bytes;
            }
            this.lastReply = bytes;
            destination = this.client;
            discard = this.fault == Fault.DROP_FIRST_REPLY && this.serverReplies == 1;
            this.observation.notifyAll();
        }
        if (!discard && destination != null) {
            this.socket.send(new DatagramPacket(bytes, bytes.length, destination));
        }
    }

    private void requireHealthy() {
        if (this.failure != null) {
            throw new AssertionError("UDP fault relay failed", this.failure);
        }
    }

    @Override
    public void close() throws InterruptedException {
        this.closed = true;
        this.socket.close();
        this.worker.join(5000);
        if (this.worker.isAlive()) {
            throw new AssertionError("UDP fault relay did not stop");
        }
        synchronized (this.observation) {
            this.requireHealthy();
        }
    }
}
