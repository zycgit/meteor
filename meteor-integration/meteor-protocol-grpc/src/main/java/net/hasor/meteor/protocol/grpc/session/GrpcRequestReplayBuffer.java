/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;

/** 请求流只订阅一次，重试重放已编码字节；超出预算后提交到一个尝试并释放历史。 */
final class GrpcRequestReplayBuffer {
    private final HttpBodyStream            source;
    private final Executor                  workers;
    private final int                       limit;
    private final Runnable                  overflow;
    private final Consumer<Throwable>       failed;
    private final List<byte[]>              chunks     = new ArrayList<>();
    private final Map<HttpBodyStream, Long> positions  = new LinkedHashMap<>();
    private       long                      offset;
    private       int                       buffered;
    private       boolean                   replayable = true;
    private       boolean                   ended;
    private       boolean                   closed;

    GrpcRequestReplayBuffer(HttpBodyStream source, Executor workers, int limit, Runnable overflow, Consumer<Throwable> failed) {
        this.source = source;
        this.workers = workers;
        this.limit = limit;
        this.overflow = overflow;
        this.failed = failed;
    }

    void start() {
        this.workers.execute(this::read);
    }

    HttpBodyStream attach() {
        HttpBodyStream body = new HttpBodyStream(65535);
        synchronized (this) {
            if (!this.replayable || this.closed) {
                throw new IllegalStateException("gRPC request has already committed");
            }
            this.positions.put(body, 0L);
            this.notifyAll();
        }
        try {
            this.workers.execute(() -> this.copy(body));
        } catch (RuntimeException failure) {
            this.detach(body);
            throw failure;
        }
        return body;
    }

    void detach(HttpBodyStream body) {
        synchronized (this) {
            this.positions.remove(body);
            this.discard();
            this.notifyAll();
        }
        body.fail(new IOException("gRPC attempt ended"));
    }

    synchronized void commit() {
        this.replayable = false;
        this.discard();
        this.notifyAll();
    }

    synchronized HttpBodyStream mostAdvanced() {
        HttpBodyStream leading = null;
        long maximum = -1;
        for (Map.Entry<HttpBodyStream, Long> entry : this.positions.entrySet()) {
            if (entry.getValue() > maximum) {
                maximum = entry.getValue();
                leading = entry.getKey();
            }
        }
        return leading;
    }

    void close(Throwable failure) {
        List<HttpBodyStream> bodies;
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            bodies = new ArrayList<>(this.positions.keySet());
            this.positions.clear();
            this.chunks.clear();
            this.buffered = 0;
            this.notifyAll();
        }
        this.source.fail(failure);
        for (HttpBodyStream body : bodies) {
            body.fail(failure);
        }
    }

    private void read() {
        try {
            byte[] bytes = new byte[16384];
            for (; ; ) {
                synchronized (this) {
                    // 无活跃尝试或超过重放预算时暂停消费业务流。
                    while (!this.closed && (this.positions.isEmpty() || this.buffered > this.limit)) {
                        this.wait();
                    }
                    if (this.closed) {
                        return;
                    }
                }
                int count = this.source.read(bytes);
                boolean commit;
                synchronized (this) {
                    if (this.closed) {
                        return;
                    }
                    if (count == -1) {
                        this.ended = true;
                        this.notifyAll();
                        return;
                    }
                    this.chunks.add(Arrays.copyOf(bytes, count));
                    this.buffered += count;
                    commit = this.replayable && this.buffered > this.limit;
                    this.notifyAll();
                }
                if (commit) {
                    this.overflow.run();
                }
            }
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (this.closed) {
                    return;
                }
            }
            this.failed.accept(failure);
        }
    }

    private void copy(HttpBodyStream body) {
        try {
            OutputStream output = body.output();
            for (; ; ) {
                byte[] chunk;
                synchronized (this) {
                    Long position = this.positions.get(body);
                    while (!this.closed && position != null && !this.ended) {
                        boolean available = position < this.offset + this.chunks.size();
                        if (available) {
                            break;
                        }
                        this.wait();
                        position = this.positions.get(body);
                    }
                    if (this.closed || position == null) {
                        return;
                    }
                    if (position == this.offset + this.chunks.size()) {
                        body.finish(this.source.trailers());
                        return;
                    }
                    chunk = this.chunks.get((int) (position - this.offset));
                    this.positions.put(body, position + 1);
                    this.discard();
                    this.notifyAll();
                }
                // 每个尝试独立等待 HTTP/2 消费额度，副本最多再保留一个有界正文窗口。
                output.write(chunk);
            }
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (this.closed || !this.positions.containsKey(body)) {
                    return;
                }
            }
            body.fail(failure);
        }
    }

    private void discard() {
        if (this.replayable || this.positions.isEmpty()) {
            return;
        }
        long consumed = this.positions.values().stream().min(Long::compare).orElse(this.offset);
        int count = (int) (consumed - this.offset);
        for (int index = 0; index < count; index++) {
            this.buffered -= this.chunks.get(index).length;
        }
        this.chunks.subList(0, count).clear();
        this.offset = consumed;
    }
}
