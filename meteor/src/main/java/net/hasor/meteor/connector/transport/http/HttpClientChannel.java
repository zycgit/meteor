/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.SocketTransport;

/**
 * Serial HTTP/1 exchanges over a reusable, lazily connected socket.
 */
final class HttpClientChannel extends HttpNetworkChannel {
    private final    InterAddress                                     remote;
    private final    BiConsumer<ProtoBuildContext, HttpClientChannel> pipeline;
    private final    Deque<HttpCall>                                  queue = new ArrayDeque<>();
    private volatile boolean                                          closed;
    private volatile NetChannel                                       channel;
    private          boolean                                          draining;
    private          HttpCall                                         active;
    private          Future<NetChannel>                               connecting;

    public HttpClientChannel(ConnectorConfig config, SocketTransport transport, InterAddress remote, Executor loop, BiConsumer<ProtoBuildContext, HttpClientChannel> pipeline) {
        super(config, transport, loop);
        this.pipeline = pipeline;
        this.remote = remote;
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
        sent.onCancel(ignored -> request.complete(new CancellationException("HTTP request cancelled")));

        if (this.closed) {
            IOException failure = new IOException("HTTP session closed");
            sent.failed(failure);
            request.complete(failure);
            return sent;
        }

        if (!this.admitWrite(sent)) {
            return sent;
        }

        executeWrite(this.executor, sent, () -> {
            this.enqueue(request, sent);
        });
        return sent;
    }

    private void enqueue(HttpExchange request, BasicFuture<Void> sent) {
        if (request.completion().isDone()) {
            sent.failed(request.completion().getCause() != null ? request.completion().getCause() : new CancellationException("RPC already completed"));
            return;
        }

        if (this.closed || this.queue.size() + (this.active == null ? 0 : 1) >= this.config.integer("maxPendingRequests", 1024)) {
            IOException failure = new IOException("HTTP session closed or request queue full");
            sent.failed(failure);
            request.complete(failure);
            return;
        }

        HttpCall call = new HttpCall(request, sent);
        this.queue.addLast(call);

        try {
            request.completion().onFinal(done -> this.executor.execute(() -> {
                this.finishCall(call, done.getCause() != null ? done.getCause() : new CancellationException("RPC completed"));
            }));
            if (!request.completion().isDone()) {
                this.next();
            }
        } catch (Throwable failure) {
            this.queue.remove(call);
            this.failCall(call, failure);
        }
    }

    private void next() {
        if (this.closed || this.active != null || this.queue.isEmpty() || this.connecting != null) {
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
                        this.failQueue(done.isCancelled() ? new CancellationException("HTTP connect cancelled") : done.getCause());
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
        HttpCall call = this.queue.removeFirst();
        this.active = call;
        try {
            HttpRequest request = call.request().request();
            if (request.body().length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
                throw new IOException("HTTP request too large");
            }

            FullHttpRequest out = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(request.method()), request.uri(), ByteBuf.wrap(request.body()));
            request.headers().forEach(out::setHeader);
            out.setHeader("Host", this.getRemote().getHostPort());
            out.removeHeader("Transfer-Encoding");
            out.setHeader("Content-Length", String.valueOf(request.body().length));
            out.setHeader("Connection", "keep-alive");
            this.writeSocket(this.channel, out).onFinal(done -> this.executor.execute(() -> {
                Throwable failure = done.isCancelled() ? new CancellationException("HTTP write cancelled") : done.getCause();
                this.completeWrite(call.sent(), null, failure);
                if (failure != null && this.active == call) {
                    this.active = null;
                    this.failCall(call, failure);
                    this.discardChannel();
                    this.next();
                } else if (this.draining && this.active == call) {
                    this.finishCall(call, new IOException("HTTP connector closing"));
                }
            }));
        } catch (Exception failure) {
            this.active = null;
            this.failCall(call, failure);
            this.next();
        }
    }

    private void finishCall(HttpCall call, Throwable cause) {
        if (this.active == call) {
            this.active = null;
            this.discardChannel();
        } else if (!this.queue.remove(call)) {
            return;
        }

        this.failCall(call, cause);
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
        if (this.active != null) {
            this.failCall(this.active, cause);
            this.active = null;
        }

        while (!this.queue.isEmpty()) {
            this.failCall(this.queue.removeFirst(), cause);
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
        this.draining = true;
        if (this.active != null && this.active.sent().isDone()) {
            this.finishCall(this.active, new IOException("HTTP connector closing"));
        }
    }

    private void finishClose(NetChannel socket) {
        if (socket == null) {
            this.forgetChannel();
        } else {
            socket.onClose(done -> this.forgetChannel());
            socket.closeNow();
        }
    }

    void receive(NetChannel socket, HttpResponse response, boolean keepAlive) {
        if (socket != this.channel || this.active == null) {
            socket.closeNow();
            return;
        }
        if (response.status() < 200) {
            return;
        }

        HttpCall call = this.active;
        this.active = null;

        try {
            call.request().respond(response);
            call.request().complete(null);
        } catch (Exception failure) {
            this.failCall(call, failure);
        }

        if (!keepAlive) {
            this.discardChannel();
        }

        this.next();
    }

    void failed(NetChannel socket, Throwable failure) {
        if (socket == this.channel) {
            this.channel = null;
            if (this.active != null) {
                HttpCall call = this.active;
                this.active = null;
                this.failCall(call, failure);
            }
            this.next();
        }
    }

    void dispatch(Runnable action) {
        this.executor.execute(action);
    }

    private void failCall(HttpCall call, Throwable error) {
        if (!call.markFailed()) {
            return;
        }

        call.request().complete(error);
        this.completeWrite(call.sent(), null, error);
    }
}
