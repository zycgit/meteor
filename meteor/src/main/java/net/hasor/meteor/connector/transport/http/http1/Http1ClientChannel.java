/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.SocketRuntime;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.http.*;

/**
 * Serial HTTP/1 exchanges over a reusable, lazily connected socket.
 */
final class Http1ClientChannel extends HttpChannel {
    private final    InterAddress                                      remote;
    private final    BiConsumer<ProtoBuildContext, Http1ClientChannel> pipeline;
    private final    Deque<Http1ClientExchangeState>                   pendingExchanges = new ArrayDeque<>();
    private volatile boolean                                           closed;
    private volatile NetChannel                                        channel;
    private volatile Http1ClientExchangeState                          activeExchange;
    private          Future<NetChannel>                                connecting;

    Http1ClientChannel(TransportConfig config, SocketRuntime transport, InterAddress remote, Executor loop, //
            RouteTable<HttpExchange> routes, String initialMountId, //
            BiConsumer<ProtoBuildContext, Http1ClientChannel> pipeline) {
        super(config, transport, loop, routes, initialMountId);
        this.pipeline = pipeline;
        this.remote = remote;
    }

    boolean attach(ChannelListenerFactory<HttpExchange> factory) throws Exception {
        return this.attachReceiver(factory);
    }

    Future<Void> closedFuture() {
        return this.closeFuture();
    }

    void notifyReady() {
        this.notifyConnected();
    }

    @Override
    public InterAddress getRemote() {
        return this.remote;
    }

    public InterAddress getLocal() {
        return this.localAddress(this.channel);
    }

    public boolean isOpen() {
        return !this.closed;
    }

    public Future<Void> write(HttpExchange request) {
        BasicFuture<Void> sent = new BasicFuture<>();
        sent.onFailed(done -> request.complete(done.getCause()));
        sent.onCancel(ignored -> request.complete(new CancellationException("HTTP request cancelled")));

        if (this.closed) {
            IOException failure = new IOException("HTTP session closed");
            sent.failed(failure);
            return sent;
        }

        if (!this.admitWrite(sent)) {
            return sent;
        }

        this.executeWrite(sent, () -> {
            this.enqueue(request, sent);
        });
        return sent;
    }

    private void enqueue(HttpExchange request, BasicFuture<Void> sent) {
        if (request.request().version() != 1) {
            IOException failure = new IOException("This request requires HTTP/2; configure connector httpVersion=2");
            sent.failed(failure);
            return;
        }

        if (request.completion().isDone()) {
            Throwable failure = request.completion().getCause();
            if (failure == null) {
                failure = new CancellationException("RPC already completed");
            }
            sent.failed(failure);
            return;
        }

        int pendingCount = this.pendingExchanges.size() + (this.activeExchange == null ? 0 : 1);
        if (this.closed || pendingCount >= this.config.integer("maxPendingRequests", 1024)) {
            IOException failure = new IOException("HTTP session closed or request queue full");
            sent.failed(failure);
            return;
        }

        Http1ClientExchangeState exchangeState = new Http1ClientExchangeState(request, sent);
        this.pendingExchanges.addLast(exchangeState);

        try {
            request.completion().onFinal(done -> this.executor.execute(() -> {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("RPC completed");
                }

                this.finishExchange(exchangeState, failure);
            }));

            if (!request.completion().isDone()) {
                this.next();
            }
        } catch (Throwable failure) {
            this.pendingExchanges.remove(exchangeState);
            this.failExchange(exchangeState, failure);
        }
    }

    private void next() {
        if (this.closed || this.activeExchange != null || this.pendingExchanges.isEmpty() || this.connecting != null) {
            return;
        }

        if (this.channel == null || this.channel.isClose()) {
            this.openSocket();
            return;
        }

        this.writeNext();
    }

    private void openSocket() {
        try {
            this.connecting = this.transport.connect(this.getRemote(), stack -> {
                this.pipeline.accept(stack, this);
            });

            this.connecting.onFinal(done -> {
                this.executor.execute(() -> {
                    this.connecting = null;
                    NetChannel socket = done.getResult();
                    if (this.closed) {
                        this.finishClose(socket);
                    } else if (done.isCancelled() || done.getCause() != null) {
                        Throwable failure = done.getCause();
                        if (done.isCancelled()) {
                            failure = new CancellationException("HTTP connect cancelled");
                        }

                        this.failQueue(failure);
                    } else {
                        this.channel = socket;
                        this.next();
                    }
                });
            });
        } catch (Exception failure) {
            this.connecting = null;
            this.failQueue(failure);
        }
    }

    private void writeNext() {
        Http1ClientExchangeState exchangeState = this.pendingExchanges.removeFirst();
        this.activeExchange = exchangeState;
        exchangeState.sent().onFinal(done -> this.execute(() -> this.written(exchangeState, done)));

        try {
            HttpRequest request = exchangeState.exchange().request();
            HttpMethod method = HttpMethod.valueOf(request.method());
            if (request.stream() != null) {
                this.writeBody(exchangeState, method);
                return;
            }
            if (request.body().length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
                throw new IOException("HTTP request too large");
            }

            ByteBuf body = ByteBuf.wrap(request.body());
            FullHttpRequest out = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, request.uri(), body);
            HttpMessageUtils.write(request.headers(), out);
            out.setHeader("Host", this.getRemote().getHostPort());
            if (request.trailers().isEmpty()) {
                out.removeHeader("Transfer-Encoding");
                out.setHeader("Content-Length", String.valueOf(request.body().length));
            } else {
                out.removeHeader("Content-Length");
                out.setHeader("Transfer-Encoding", "chunked");
                HttpMessageUtils.write(request.trailers(), out.trailingHeaders());
            }

            if (request.header("Upgrade") == null) {
                out.setHeader("Connection", "keep-alive");
            }
            this.writeSocket(this.channel, out).onFinal(done -> {
                this.completeWrite(exchangeState.sent(), null, HttpMessageUtils.writeFailure(done));
            });
        } catch (Exception failure) {
            exchangeState.sent().failed(failure);
        }
    }

    private void writeBody(Http1ClientExchangeState exchangeState, HttpMethod method) {
        HttpRequest request = exchangeState.exchange().request();
        HttpBodyStream bodyStream = request.stream();
        DefaultHttpRequest start = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, request.uri());
        LastHttpHeaders headers = new DefaultLastHttpHeaders();
        HttpMessageUtils.write(request.headers(), headers);
        headers.setHeader("Host", this.getRemote().getHostPort());
        headers.setHeader("Connection", "keep-alive");
        headers.removeHeader("Content-Length");
        headers.setHeader("Transfer-Encoding", "chunked");

        NetChannel socket = this.channel;
        this.writeSocket(socket, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
            Throwable failure = HttpMessageUtils.writeFailure(done);
            if (failure == null) {
                Function<Object, Future<?>> writer = message -> this.writeSocket(socket, message);
                this.startStreamingBodyWrite(0, bodyStream, request.trailers(), writer, exchangeState.sent());
            } else {
                this.failExchange(exchangeState, failure);
            }
        }));
    }

    private void written(Http1ClientExchangeState exchangeState, Future<?> done) {
        Throwable failure = HttpMessageUtils.writeFailure(done);
        if (failure != null) {
            this.finishExchange(exchangeState, failure);
        } else if (this.isDraining() && this.activeExchange == exchangeState) {
            this.finishExchange(exchangeState, new IOException("HTTP connector closing"));
        } else {
            this.finishResponse(exchangeState);
        }
    }

    private void finishExchange(Http1ClientExchangeState exchangeState, Throwable cause) {
        if (this.activeExchange == exchangeState) {
            this.activeExchange = null;
            this.discardChannel();
        } else if (!this.pendingExchanges.remove(exchangeState)) {
            return;
        }

        this.failExchange(exchangeState, cause);
        this.next();
    }

    private void discardChannel() {
        NetChannel old = this.channel;
        this.channel = null;
        if (old != null) {
            old.closeNow();
        }
    }

    private void failQueue(Throwable cause) {
        if (this.activeExchange != null) {
            this.failExchange(this.activeExchange, cause);
            this.activeExchange = null;
        }

        while (!this.pendingExchanges.isEmpty()) {
            this.failExchange(this.pendingExchanges.removeFirst(), cause);
        }
    }

    public Future<Void> close() {
        if (this.closed) {
            return this.closeFuture();
        }

        this.closed = true;
        try {
            this.executor.execute(() -> {
                try {
                    this.closeUpgrades(new IOException("HTTP connector closed"));
                    this.failQueue(new IOException("HTTP connector closed"));
                } finally {
                    NetChannel old = this.channel;
                    if (this.connecting != null) {
                        old = this.connecting.getResult();
                        this.connecting.cancel();
                    }

                    this.channel = null;
                    this.finishClose(old);
                }
            });
        } catch (Throwable failure) {
            this.closeFuture().failed(failure);
        }
        return this.closeFuture();
    }

    @Override
    protected void prepareDrain() {
        if (this.activeExchange != null && this.activeExchange.sent().isDone()) {
            this.finishExchange(this.activeExchange, new IOException("HTTP connector closing"));
        }
    }

    private void finishClose(NetChannel socket) {
        if (socket == null) {
            this.notifyClosed();
        } else {
            socket.onClose(done -> this.notifyClosed());
            socket.closeNow();
        }
    }

    boolean streamResponse() {
        Http1ClientExchangeState exchangeState = this.activeExchange;
        if (exchangeState == null) {
            return false;
        }
        HttpRequest request = exchangeState.exchange().request();
        return request.stream() != null || this.streamResponseBody();
    }

    HttpExchange upgraded(NetChannel socket) {
        if (this.closed || this.channel != socket || this.activeExchange == null) {
            throw new IllegalStateException("HTTP upgrade no longer has an active request");
        }
        HttpExchange exchange = this.activeExchange.exchange();
        this.activeExchange = null;
        this.channel = null;
        // 升级连接由独立通道接管；后续 HTTP exchange 使用新的物理连接。
        this.next();
        return exchange;
    }

    void receive(NetChannel socket, HttpResponse response, boolean keepAlive) {
        if (socket != this.channel || this.activeExchange == null) {
            socket.closeNow();
            return;
        }
        if (response.status() < 200) {
            return;
        }

        Http1ClientExchangeState exchangeState = this.activeExchange;
        exchangeState.response(response, keepAlive);
        if (response.stream() != null) {
            response.stream().completion().onFinal(done -> this.execute(() -> {
                if (!done.isCancelled() && done.getCause() == null) {
                    this.finishResponse(exchangeState);
                } else {
                    this.finishExchange(exchangeState, done.getCause());
                }
            }));
        }
        exchangeState.exchange().respond(response);
        this.finishResponse(exchangeState);
    }

    private void finishResponse(Http1ClientExchangeState exchangeState) {
        Future<Void> sent = exchangeState.sent();
        boolean written = sent.isDone() && !sent.isCancelled() && sent.getCause() == null;
        if (this.activeExchange != exchangeState || exchangeState.response() == null || !written) {
            return;
        }
        HttpBodyStream bodyStream = exchangeState.response().stream();
        if (bodyStream != null && (//
                !bodyStream.completion().isDone() || bodyStream.completion().getCause() != null)) {
            return;
        }

        // 完整消费响应且上传写完之后，才允许下一个请求复用这条 HTTP/1 连接。
        this.activeExchange = null;
        exchangeState.exchange().complete(null);
        if (!exchangeState.keepAlive()) {
            this.discardChannel();
        }
        this.next();
    }

    void failed(NetChannel socket, Throwable failure) {
        if (socket != this.channel) {
            return;
        }
        this.channel = null;
        Http1ClientExchangeState exchangeState = this.activeExchange;
        if (exchangeState != null) {
            HttpResponse response = exchangeState.response();
            HttpBodyStream bodyStream = response == null ? null : response.stream();
            boolean received = response != null && (bodyStream == null || bodyStream.inputEnded());
            Future<Void> sent = exchangeState.sent();
            boolean written = sent.isDone() && !sent.isCancelled() && sent.getCause() == null;
            if (received && written) {
                // EOF 之后，已经完整接收的正文仍可由应用读完。
                this.finishResponse(exchangeState);
                return;
            }
            this.activeExchange = null;
            this.failExchange(exchangeState, failure);
        }
        this.next();
    }

    private void failExchange(Http1ClientExchangeState exchangeState, Throwable error) {
        if (!exchangeState.markFailed()) {
            return;
        }

        exchangeState.exchange().complete(error);
        this.completeWrite(exchangeState.sent(), null, error);
    }
}
