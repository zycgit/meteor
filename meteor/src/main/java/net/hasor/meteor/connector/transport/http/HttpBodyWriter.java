/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.*;

/** 每次只提交一块正文，物理写入完成后继续，避免阻塞连接执行器。 */
final class HttpBodyWriter {
    private final Map<String, List<String>>   trailers;
    private final long                        id;
    private final HttpBody                    body;
    private final Consumer<Runnable>          execute;
    private final Function<Object, Future<?>> write;
    private final BasicFuture<Void>           sent;
    private       boolean                     writing;

    public HttpBodyWriter(long id, HttpBody body, Map<String, List<String>> trailers, Consumer<Runnable> execute, Function<Object, Future<?>> write, BasicFuture<Void> sent) {
        this.id = id;
        this.trailers = trailers;
        this.body = body;
        this.execute = execute;
        this.write = write;
        this.sent = sent;
    }

    public void start() {
        this.body.onReadable(() -> this.execute.accept(this::next));
        this.next();
    }

    private void next() {
        if (this.writing || this.sent.isDone()) {
            return;
        }

        try {
            HttpBody.Chunk chunk = this.body.poll();
            if (chunk == null) {
                if (this.body.ended()) {
                    this.finish();
                }
                return;
            }

            this.writing = true;
            Future<?> submitted;
            try {
                submitted = this.writeChunk(chunk);
            } catch (Throwable failure) {
                this.body.consumed(chunk);
                throw failure;
            }
            submitted.onFinal(done -> this.execute.accept(() -> {
                this.body.consumed(chunk);
                this.writing = false;
                if (this.success(done)) {
                    this.next();
                }
            }));
        } catch (Throwable failure) {
            this.body.fail(failure);
            this.sent.failed(failure);
        }
    }

    private Future<?> writeChunk(HttpBody.Chunk chunk) {
        // HTTP/2 窗口不足时，sendData 完成只代表本轮已编码的数据写完，正文可能仍留在编码队列。
        BasicFuture<Void> encoded = new BasicFuture<>();
        HttpContent content = new DefaultHttpContent(ByteBuf.wrap(chunk.bytes())) {
            @Override
            public void release() {
                super.release();
                encoded.completed(null);
            }
        };
        content.streamId(this.id);
        BasicFuture<Void> written = new BasicFuture<>();
        this.write.apply(content).onFinal(submitted -> {
            if (submitted.getCause() != null) {
                written.failed(submitted.getCause());
            } else if (submitted.isCancelled()) {
                written.cancel();
            } else {
                encoded.onCompleted(done -> this.execute.accept(() -> this.flushChunk(written)));
            }
        });
        return written;
    }

    private void flushChunk(BasicFuture<Void> written) {
        // 在连接执行器中追加写屏障，等待窗口恢复后编码的数据落到网络，再归还正文缓冲额度。
        try {
            this.write.apply(new Object[0]).onFinal(done -> {
                if (done.getCause() != null) {
                    written.failed(done.getCause());
                } else if (done.isCancelled()) {
                    written.cancel();
                } else {
                    written.completed(null);
                }
            });
        } catch (Throwable failure) {
            written.failed(failure);
        }
    }

    private void finish() {
        this.writing = true;
        LastHttpContent last = new DefaultLastHttpContent(ByteBuf.EMPTY);
        last.streamId(this.id);
        Object end = last;
        if (!this.trailers.isEmpty() || !this.body.trailers().isEmpty()) {
            TrailerHttpHeaders trailers = new DefaultTrailerHttpHeaders();
            trailers.streamId(this.id);
            HttpMessages.write(this.trailers, trailers);
            HttpMessages.write(this.body.trailers(), trailers);
            end = new Object[] { trailers, last };
        }

        this.write.apply(end).onFinal(done -> this.execute.accept(() -> {
            if (this.success(done)) {
                this.body.sent();
                this.sent.completed(null);
            }
        }));
    }

    private boolean success(Future<?> done) {
        Throwable failure = done.getCause();
        if (done.isCancelled()) {
            failure = new IOException("HTTP content write cancelled");
        }
        if (failure == null) {
            return true;
        }

        this.body.fail(failure);
        this.sent.failed(failure);
        return false;
    }
}