/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.address.InterAddress;

/** 保留 RPC 正文，仅重写 TCP record marking 的透明测试中继。 */
final class OncRpcRecordRelay implements AutoCloseable {
    private static final int                        MAX_RECORD_SIZE   = 8 * 1024 * 1024;
    private static final int                        FRAGMENT_SIZE     = 1024;
    private final        ServerSocket               listener;
    private final        ExecutorService            executor;
    private final        List<Socket>               sockets           = new ArrayList<>();
    private final        AtomicBoolean              closing           = new AtomicBoolean();
    private final        AtomicReference<Throwable> failure           = new AtomicReference<>();
    private final        AtomicInteger              requestFragments  = new AtomicInteger();
    private final        AtomicInteger              responseFragments = new AtomicInteger();

    OncRpcRecordRelay(InterAddress target) throws IOException {
        // 中继独占监听、接受的连接和三个任务线程，调用方通过 close 统一回收。
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.executor = Executors.newFixedThreadPool(3);
        this.executor.execute(() -> this.accept(target));
    }

    int getPort() {
        return this.listener.getLocalPort();
    }

    int getRequestFragments() {
        return this.requestFragments.get();
    }

    int getResponseFragments() {
        return this.responseFragments.get();
    }

    void assertHealthy() {
        Throwable error = this.failure.get();
        if (error != null) {
            throw new AssertionError("ONC RPC record relay failed", error);
        }
    }

    private void accept(InterAddress target) {
        try {
            Socket client = this.listener.accept();
            if (!this.register(client)) {
                return;
            }
            Socket server = new Socket();
            if (!this.register(server)) {
                return;
            }
            server.connect(new InetSocketAddress(target.getHost(), target.getPort()), 5000);
            client.setTcpNoDelay(true);
            server.setTcpNoDelay(true);

            // 双向都重建 record fragment，确保请求和响应各自经过真正的记录重组。
            this.executor.execute(() -> this.forward(client, server, this.requestFragments));
            this.executor.execute(() -> this.forward(server, client, this.responseFragments));
        } catch (Throwable error) {
            this.failed(error);
        }
    }

    private boolean register(Socket socket) throws IOException {
        synchronized (this.sockets) {
            if (this.closing.get()) {
                socket.close();
                return false;
            }
            this.sockets.add(socket);
            return true;
        }
    }

    private void forward(Socket source, Socket target, AtomicInteger fragments) {
        try {
            DataInputStream input = new DataInputStream(source.getInputStream());
            DataOutputStream output = new DataOutputStream(target.getOutputStream());
            while (!this.closing.get()) {
                byte[] body = this.readRecord(input);
                if (body == null) {
                    return;
                }
                this.writeFragments(output, body, fragments);
            }
        } catch (Throwable error) {
            this.failed(error);
        }
    }

    private byte[] readRecord(DataInputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int fragments = 0;
        while (true) {
            // marker 的最高位表示最后一个 fragment，其余 31 位表示当前 fragment 的字节数。
            int firstByte = input.read();
            if (firstByte < 0 && fragments == 0) {
                return null;
            }
            if (firstByte < 0) {
                throw new EOFException("ONC RPC record ended before its final fragment");
            }
            int marker = firstByte << 24 | input.readUnsignedByte() << 16;
            marker |= input.readUnsignedByte() << 8 | input.readUnsignedByte();
            int length = marker & 0x7fffffff;
            fragments++;
            if (length > MAX_RECORD_SIZE - body.size() || fragments > 65536) {
                throw new IOException("ONC RPC relay record exceeds its bounded input");
            }
            byte[] fragment = input.readNBytes(length);
            if (fragment.length != length) {
                throw new EOFException("ONC RPC fragment ended before its declared length");
            }
            body.write(fragment);
            if ((marker & 0x80000000) != 0) {
                return body.toByteArray();
            }
        }
    }

    private void writeFragments(DataOutputStream output, byte[] body, AtomicInteger fragments) throws IOException {
        int offset = 0;
        do {
            // 每段最多 1024 字节，只改变 record marking，不改 RPC 正文。
            int length = Math.min(FRAGMENT_SIZE, body.length - offset);
            boolean last = offset + length == body.length;
            int marker = length;
            if (last) {
                marker |= 0x80000000;
            }
            output.writeInt(marker);
            output.write(body, offset, length);
            offset += length;
            fragments.incrementAndGet();
        } while (offset < body.length);
        output.flush();
    }

    private void failed(Throwable error) {
        if (!this.closing.get()) {
            this.failure.compareAndSet(null, error);
        }
    }

    @Override
    public void close() throws Exception {
        this.closing.set(true);
        this.closeResource(this.listener);
        synchronized (this.sockets) {
            for (Socket socket : this.sockets) {
                this.closeResource(socket);
            }
        }

        // 关闭 socket 唤醒阻塞读取，再等待所有自建任务退出。
        this.executor.shutdownNow();
        if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IOException("ONC RPC record relay did not stop");
        }
        this.assertHealthy();
    }

    private void closeResource(Closeable resource) {
        try {
            resource.close();
        } catch (IOException error) {
            this.failure.compareAndSet(null, error);
        }
    }
}
