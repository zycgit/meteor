/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ReadPause;
import net.hasor.neta.codec.http.*;

/**
 * Owns incoming HTTP request routes and preserves HTTP/1 response order.
 */
class HttpServerChannel extends HttpNetworkChannel {
    private       boolean                      inputClosed;
    private       HttpReply                    writing;
    private final NetChannel                   channel;
    private final InterAddress                 remote;
    private final Deque<HttpReply>             replies = new ArrayDeque<>();
    private final Map<HttpExchange, HttpReply> pending = new HashMap<>();

    public HttpServerChannel(ConnectorConfig config, SocketTransport transport, NetChannel channel, Executor executor) {
        super(config, transport, executor);
        this.channel = channel;

        InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddr();
        String scheme = this.config.address().getSchema();
        String host = remote.getAddress().getHostAddress();
        this.remote = new InterAddress(scheme, host, remote.getPort(), "unknown");
    }

    protected void connected() {
        this.executor.execute(() -> {
            try {
                this.manageChannel();
            } catch (Throwable failure) {
                this.close();
            }
        });
    }

    void received(HttpRequest request, boolean keepAlive) {
        ReadPause pause = this.channel.pauseRead();
        this.executor.execute(() -> {
            try {
                this.receive(request, keepAlive);
            } finally {
                this.channel.resumeRead(pause);
            }
        });
    }

    protected void terminated() {
        this.executor.execute(() -> {
            try {
                this.cancel();
            } finally {
                this.forgetChannel();
            }
        });
    }

    protected void inputClosed() {
        this.inputClosed = true;
        if (this.replies.isEmpty() && this.writing == null) {
            this.drainAndClose();
        }
    }

    @Override
    public InterAddress getRemote() {
        return this.remote;
    }

    @Override
    public InterAddress getLocal() {
        return this.localAddress(this.channel);
    }

    @Override
    public boolean isOpen() {
        return !this.channel.isClose();
    }

    @Override
    public Future<Void> close() {
        this.channel.closeNow();
        return this.closeFuture();
    }

    private void receive(HttpRequest request, boolean keepAlive) {
        if (this.channel.isClose() || this.replies.size() >= this.config.integer("maxPendingRequests", 1024)) {
            this.channel.closeNow();
            return;
        }

        HttpExchange exchange = new HttpExchange(request);
        int expectation = HttpMessages.expectation(request);
        HttpReply slot = new HttpReply(keepAlive && expectation != 417, exchange, expectation == 100);
        this.replies.addLast(slot);
        this.pending.put(exchange, slot);

        exchange.completion().onFinal(done -> this.executor.execute(() -> {
            if (this.pending.remove(exchange, slot)) {
                this.finish(slot, unavailable(), null);
            }
        }));

        if (expectation == 417) {
            this.pending.remove(exchange);
            this.finish(slot, HttpResponse.newBuffered(417, Map.of(), new byte[0], Map.of()), null);
            return;
        }

        try {
            this.drain();
            if (request.stream() != null) {
                request.stream().completion().onFailed(done -> {
                    this.execute(this::close);
                });
            }

            this.received(exchange);
        } catch (Exception failure) {
            exchange.complete(failure);
        }
    }

    public Future<Void> write(HttpExchange exchange) {
        BasicFuture<Void> sent = new BasicFuture<>();
        if (this.admitWrite(sent)) {
            this.executeWrite(this.executor, sent, () -> {
                if (sent.isCancelled()) {
                    return;
                }

                HttpReply slot = this.pending.get(exchange);
                if (!this.isOpen() || slot == null || exchange.response().getResult() == null) {
                    sent.failed(new IllegalStateException("HTTP response has no pending exchange on this channel"));
                    return;
                }

                this.pending.remove(exchange);
                this.finish(slot, exchange.response().getResult(), sent);
            });
        }
        return sent;
    }

    private static HttpResponse unavailable() {
        return HttpResponse.newBuffered(503, Collections.emptyMap(), new byte[0], Map.of());
    }

    private void drain() {
        if (this.writing != null || this.replies.isEmpty()) {
            return;
        }

        HttpReply first = this.replies.peekFirst();
        if (first.takeContinue()) {
            this.writeContinue(first);
            return;
        }
        if (first.response() == null) {
            return;
        }

        // 一个响应包含头部、所有正文块和 trailers；完成后才轮到后续响应。
        HttpReply slot = this.replies.removeFirst();
        this.writing = slot;
        BasicFuture<Void> sent = new BasicFuture<>();
        sent.onFinal(done -> this.execute(() -> this.written(slot, done)));
        try {
            HttpResponse response = slot.response();
            if (response.stream() != null) {
                this.writeBody(slot, sent);
                return;
            }
            if (response.body().length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
                throw new IOException("HTTP response too large");
            }

            HttpStatus status = HttpStatus.valueOf(response.status());
            ByteBuf body = ByteBuf.wrap(response.body());
            FullHttpResponse out = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
            HttpMessages.write(response.headers(), out);
            if (response.trailers().isEmpty()) {
                out.removeHeader("Transfer-Encoding");
                out.setHeader("Content-Length", String.valueOf(response.body().length));
            } else {
                out.removeHeader("Content-Length");
                out.setHeader("Transfer-Encoding", "chunked");
                HttpMessages.write(response.trailers(), out.trailingHeaders());
            }
            out.setHeader("Connection", slot.keepAlive() ? "keep-alive" : "close");
            this.writeSocket(this.channel, out).onFinal(done -> this.completeWrite(sent, null, writeFailure(done)));
        } catch (Exception failure) {
            sent.failed(failure);
        }
    }

    private void writeContinue(HttpReply slot) {
        // 100 Continue 同样遵守 HTTP/1 响应顺序，不能越过前一个请求的正文。
        this.writing = slot;
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CONTINUE, ByteBuf.EMPTY);
        this.writeSocket(this.channel, response).onFinal(done -> this.execute(() -> {
            this.writing = null;
            Throwable failure = writeFailure(done);
            if (failure != null) {
                this.failReply(slot, failure);
                this.close();
            } else {
                this.drain();
            }
        }));
    }

    private void writeBody(HttpReply slot, BasicFuture<Void> sent) {
        HttpResponse response = slot.response();
        HttpBody body = response.stream();
        HttpStatus status = HttpStatus.valueOf(response.status());
        DefaultHttpResponse start = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        LastHttpHeaders headers = new DefaultLastHttpHeaders();
        HttpMessages.write(response.headers(), headers);
        headers.removeHeader("Content-Length");
        headers.setHeader("Transfer-Encoding", "chunked");
        headers.setHeader("Connection", slot.keepAlive() ? "keep-alive" : "close");

        if (slot.sent() != null) {
            slot.sent().onCancel(done -> body.fail(new CancellationException("HTTP response cancelled")));
        }
        HttpBodyWriter writer = new HttpBodyWriter(0, body, response.trailers(), this::execute, message -> this.writeSocket(this.channel, message), sent);
        this.writeSocket(this.channel, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
            Throwable failure = writeFailure(done);
            if (failure == null) {
                writer.start();
            } else {
                body.fail(failure);
                sent.failed(failure);
            }
        }));
    }

    private static Throwable writeFailure(Future<?> done) {
        return done.isCancelled() ? new CancellationException("HTTP write cancelled") : done.getCause();
    }

    private void written(HttpReply slot, Future<?> done) {
        this.writing = null;
        Throwable failure = writeFailure(done);
        this.completeWrite(slot.sent(), null, failure);
        slot.exchange().complete(failure);

        // 提前结束响应而未消费请求体时，关闭此 HTTP/1 连接，避免剩余正文阻塞后续请求。
        HttpBody request = slot.exchange().request().stream();
        boolean unread = request != null && !request.completion().isDone();
        if (unread) {
            request.fail(new IOException("HTTP response ended before request body was consumed"));
        }
        if (failure != null || !slot.keepAlive() || unread) {
            this.channel.closeNow();
        } else if (this.inputClosed && this.replies.isEmpty()) {
            this.drainAndClose();
        } else {
            this.drain();
        }
    }

    @Override
    protected void prepareDrain() {
        // HTTP/1 cannot skip an unanswered request before a submitted response.
        for (HttpReply slot : new ArrayList<>(this.pending.values())) {
            this.pending.remove(slot.exchange(), slot);
            IOException failure = new IOException("HTTP connector closing");
            slot.exchange().complete(failure);
            this.finish(slot, unavailable(), null);
        }
    }

    private void cancel() {
        IOException failure = new IOException("HTTP connection closed");
        if (this.writing != null) {
            this.failReply(this.writing, failure);
        }
        for (HttpReply slot : this.replies) {
            this.failReply(slot, failure);
        }
        this.pending.clear();
        this.replies.clear();
    }

    private void failReply(HttpReply slot, Throwable failure) {
        slot.exchange().complete(failure);
        this.completeWrite(slot.sent(), null, failure);
    }

    private void finish(HttpReply slot, HttpResponse response, BasicFuture<Void> sent) {
        if (slot.response() != null || this.channel.isClose()) {
            this.completeWrite(sent, null, new IOException("HTTP request is closed"));
            return;
        }

        slot.prepareResponse(response, sent);
        this.drain();
    }
}
