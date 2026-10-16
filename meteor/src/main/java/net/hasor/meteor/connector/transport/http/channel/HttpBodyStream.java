/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;

/** 有界的增量 HTTP 正文；读写可阻塞，必须在业务线程执行，不能进入网络回调。 */
public final class HttpBodyStream extends InputStream {
    private final int                       capacity;
    private final ArrayDeque<Chunk>         chunks     = new ArrayDeque<>();
    private final BasicFuture<Void>         completion = new BasicFuture<>();
    private       int                       buffered;
    private       Chunk                     reading;
    private       int                       offset;
    private       boolean                   ended;
    private       IOException               failure;
    private       Map<String, List<String>> trailers   = Map.of();
    private       Runnable                  readable   = () -> {
    };

    public HttpBodyStream(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("HTTP body capacity must be positive");
        }
        this.capacity = capacity;
    }

    public Future<Void> completion() {
        return this.completion;
    }

    public synchronized Map<String, List<String>> trailers() {
        return this.trailers;
    }

    /** 正文发送完毕后结束此方向，尾部字段与正文一起按序发送。 */
    public void finish(Map<String, List<String>> trailers) {
        Runnable notify;
        synchronized (this) {
            if (this.ended || this.failure != null) {
                return;
            }

            this.ended = true;
            this.trailers = HttpMessageUtils.copy(trailers);
            this.notifyAll();
            notify = this.readable;
        }
        notify.run();
    }

    /** 输出端只在空间不足时等待；close 表示 EOF，不关闭同一连接的其他交换。 */
    public OutputStream output() {
        return new OutputStream() {
            public void write(int value) throws IOException {
                this.write(new byte[] { (byte) value });
            }

            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                while (length > 0) {
                    int count = Math.min(length, Math.min(16384, HttpBodyStream.this.capacity));
                    HttpBodyStream.this.put(Arrays.copyOfRange(bytes, offset, offset + count));
                    offset += count;
                    length -= count;
                }
            }

            public void close() {
                HttpBodyStream.this.finish(Map.of());
            }
        };
    }

    private void put(byte[] bytes) throws IOException {
        Runnable notify;
        synchronized (this) {
            while (this.buffered + bytes.length > this.capacity && !this.ended && this.failure == null) {
                this.await();
            }
            this.checkFailure();
            if (this.ended) {
                throw new IOException("HTTP body output is closed");
            }

            this.chunks.add(new Chunk(bytes, () -> {
            }));
            this.buffered += bytes.length;
            this.notifyAll();
            notify = this.readable;
        }
        notify.run();
    }

    /** 接收回调不等待；持有的网络额度在应用消费该块之后归还。 */
    public void offer(byte[] bytes, Runnable release) throws IOException {
        Runnable notify;
        synchronized (this) {
            this.checkFailure();
            if (this.ended || this.buffered + bytes.length > this.capacity) {
                throw new IOException("HTTP body input exceeds its receive window");
            }

            this.chunks.add(new Chunk(bytes, release));
            this.buffered += bytes.length;
            this.notifyAll();
            notify = this.readable;
        }
        notify.run();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return this.read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int start, int length) throws IOException {
        Objects.checkFromIndexSize(start, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        synchronized (this) {
            for (; ; ) {
                this.checkFailure();
                if (this.reading == null) {
                    this.reading = this.chunks.poll();
                    this.offset = 0;
                }

                if (this.reading != null) {
                    int count = Math.min(length, this.reading.bytes.length - this.offset);
                    System.arraycopy(this.reading.bytes, this.offset, bytes, start, count);
                    this.offset += count;
                    if (this.offset == this.reading.bytes.length) {
                        this.consumed(this.reading);
                        this.reading = null;
                    }
                    if (count > 0) {
                        return count;
                    }
                } else if (this.ended) {
                    break;
                } else {
                    this.await();
                }
            }
        }
        this.completion.completed(null);
        return -1;
    }

    private void await() throws InterruptedIOException {
        try {
            this.wait();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("HTTP body operation interrupted");
        }
    }

    private void checkFailure() throws IOException {
        if (this.failure != null) {
            throw this.failure;
        }
    }

    @Override
    public void close() {
        this.fail(new IOException("HTTP body input closed"));
    }

    public void fail(Throwable cause) {
        Runnable notify;
        synchronized (this) {
            if (this.failure != null || this.completion.isDone()) {
                return;
            }

            this.failure = cause instanceof IOException io ? io : new IOException("HTTP body failed", cause);
            if (this.reading != null) {
                this.consumed(this.reading);
                this.reading = null;
            }

            Chunk chunk;
            while ((chunk = this.chunks.poll()) != null) {
                this.consumed(chunk);
            }
            this.notifyAll();
            notify = this.readable;
        }
        this.completion.failed(this.failure);
        notify.run();
    }

    // HTTP 写入器逐块提交，写入完成后才释放缓冲空间。
    synchronized void onReadable(Runnable action) {
        this.readable = action;
    }

    synchronized Chunk poll() throws IOException {
        this.checkFailure();
        return this.chunks.poll();
    }

    synchronized boolean ended() {
        return this.ended && this.buffered == 0;
    }

    public synchronized boolean inputEnded() {
        return this.ended;
    }

    public synchronized int writableBytes() throws IOException {
        this.checkFailure();
        return this.capacity - this.buffered;
    }

    synchronized void consumed(Chunk chunk) {
        this.buffered -= chunk.bytes.length;
        chunk.release.run();
        this.notifyAll();
    }

    void sent() {
        this.completion.completed(null);
    }

    static final class Chunk {
        private final byte[]   bytes;
        private final Runnable release;

        private Chunk(byte[] bytes, Runnable release) {
            this.bytes = bytes;
            this.release = release;
        }

        byte[] bytes() {
            return this.bytes;
        }

        Runnable release() {
            return this.release;
        }
    }
}
