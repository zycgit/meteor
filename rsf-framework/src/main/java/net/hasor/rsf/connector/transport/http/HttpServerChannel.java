/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ReadPause;
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.SocketTransport;

/**
 * Owns incoming HTTP request routes and preserves HTTP/1 response order.
 */
class HttpServerChannel extends HttpNetworkChannel {
    private final NetChannel                   channel;
    private final InterAddress                 remote;
    private final Deque<HttpReply>             replies = new ArrayDeque<>();
    private final Map<HttpExchange, HttpReply> pending = new HashMap<>();

    public HttpServerChannel(ConnectorConfig config, SocketTransport transport, NetChannel channel, Executor executor) {
        super(config, transport, executor);
        this.channel = channel;

        InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddr();
        this.remote = new InterAddress(this.config.address().getSchema(), remote.getAddress().getHostAddress(), remote.getPort(), "unknown");
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

    protected void received(HttpRequest request, boolean keepAlive, String route) {
        ReadPause pause = this.channel.pauseRead();
        this.executor.execute(() -> {
            try {
                this.receive(request, keepAlive, route);
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

    protected void receive(HttpRequest request, boolean keepAlive, String route) {
        if (this.channel.isClose() || this.replies.size() >= this.config.integer("maxPendingRequests", 1024)) {
            this.channel.closeNow();
            return;
        }

        HttpExchange exchange = new HttpExchange(request);
        HttpReply slot = new HttpReply(keepAlive, exchange);
        this.replies.addLast(slot);
        this.pending.put(exchange, slot);

        exchange.completion().onFinal(done -> this.executor.execute(() -> {
            if (this.pending.remove(exchange, slot)) {
                this.finish(slot, unavailable(), null);
            }
        }));

        try {
            this.received(route, exchange);
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
        return new HttpResponse(503, Collections.emptyMap(), new byte[0]);
    }

    private void drain() {
        while (!this.replies.isEmpty() && this.replies.peekFirst().response() != null) {
            HttpReply slot = this.replies.removeFirst();
            HttpResponse response = slot.response();
            if (response.body().length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
                this.completeWrite(slot.sent(), null, new IOException("HTTP response too large"));
                this.channel.closeNow();
                return;
            }

            FullHttpResponse out = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.valueOf(response.status()), ByteBuf.wrap(response.body()));
            response.headers().forEach(out::setHeader);
            out.removeHeader("Transfer-Encoding");
            out.setHeader("Content-Length", String.valueOf(response.body().length));
            out.setHeader("Connection", slot.keepAlive() ? "keep-alive" : "close");
            this.writeSocket(this.channel, out).onFinal(done -> {
                Throwable failure = done.isCancelled() ? new CancellationException("HTTP write cancelled") : done.getCause();
                this.completeWrite(slot.sent(), null, failure);
                slot.exchange().complete(failure);
                if (failure != null || !slot.keepAlive()) {
                    this.channel.closeNow();
                }
            });

            if (!slot.keepAlive()) {
                this.cancel();
                return;
            }
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
        for (HttpReply slot : this.pending.values()) {
            slot.exchange().complete(new IOException("HTTP connection closed"));
        }

        this.pending.clear();
        for (HttpReply slot : this.replies) {
            this.completeWrite(slot.sent(), null, new IOException("HTTP connection closed"));
        }

        this.replies.clear();
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
