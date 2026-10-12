/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.*;

/** 每次只提交一块正文，物理写入完成后继续，避免阻塞连接执行器。 */
final class Http2BodyWriter {
    private final long                        id;
    private final HttpBody                    body;
    private final Consumer<Runnable>          execute;
    private final Function<Object, Future<?>> write;
    private final BasicFuture<Void>           sent;
    private       boolean                     writing;

    public Http2BodyWriter(long id, HttpBody body, Consumer<Runnable> execute, Function<Object, Future<?>> write, BasicFuture<Void> sent) {
        this.id = id;
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
            HttpContent content = new DefaultHttpContent(ByteBuf.wrap(chunk.bytes));
            content.streamId(this.id);
            this.write.apply(content).onFinal(done -> this.execute.accept(() -> {
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

    private void finish() {
        this.writing = true;
        LastHttpContent last = new DefaultLastHttpContent(ByteBuf.EMPTY);
        last.streamId(this.id);
        Object end = last;
        if (!this.body.trailers().isEmpty()) {
            TrailerHttpHeaders trailers = new DefaultTrailerHttpHeaders();
            trailers.streamId(this.id);
            this.body.trailers().forEach(trailers::setHeader);
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
            failure = new IOException("HTTP/2 content write cancelled");
        }
        if (failure == null) {
            return true;
        }

        this.body.fail(failure);
        this.sent.failed(failure);
        return false;
    }
}
