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
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.http.*;

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
    private volatile HttpCall                                         active;
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

        executeWrite(this.executor, sent, () -> {
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

        int pendingCount = this.queue.size() + (this.active == null ? 0 : 1);
        if (this.closed || pendingCount >= this.config.integer("maxPendingRequests", 1024)) {
            IOException failure = new IOException("HTTP session closed or request queue full");
            sent.failed(failure);
            return;
        }

        HttpCall call = new HttpCall(request, sent);
        this.queue.addLast(call);

        try {
            request.completion().onFinal(done -> this.executor.execute(() -> {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("RPC completed");
                }

                this.finishCall(call, failure);
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
        HttpCall call = this.queue.removeFirst();
        this.active = call;
        call.sent().onFinal(done -> this.execute(() -> this.written(call, done)));

        try {
            HttpRequest request = call.request().request();
            HttpMethod method = HttpMethod.valueOf(request.method());
            if (request.stream() != null) {
                this.writeBody(call, method);
                return;
            }
            if (request.body().length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
                throw new IOException("HTTP request too large");
            }

            ByteBuf body = ByteBuf.wrap(request.body());
            FullHttpRequest out = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, request.uri(), body);
            HttpMessages.write(request.headers(), out);
            out.setHeader("Host", this.getRemote().getHostPort());
            if (request.trailers().isEmpty()) {
                out.removeHeader("Transfer-Encoding");
                out.setHeader("Content-Length", String.valueOf(request.body().length));
            } else {
                out.removeHeader("Content-Length");
                out.setHeader("Transfer-Encoding", "chunked");
                HttpMessages.write(request.trailers(), out.trailingHeaders());
            }

            out.setHeader("Connection", "keep-alive");
            this.writeSocket(this.channel, out).onFinal(done -> {
                this.completeWrite(call.sent(), null, writeFailure(done));
            });
        } catch (Exception failure) {
            call.sent().failed(failure);
        }
    }

    private void writeBody(HttpCall call, HttpMethod method) {
        HttpRequest request = call.request().request();
        HttpBody body = request.stream();
        DefaultHttpRequest start = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, request.uri());
        LastHttpHeaders headers = new DefaultLastHttpHeaders();
        HttpMessages.write(request.headers(), headers);
        headers.setHeader("Host", this.getRemote().getHostPort());
        headers.setHeader("Connection", "keep-alive");
        headers.removeHeader("Content-Length");
        headers.setHeader("Transfer-Encoding", "chunked");

        NetChannel socket = this.channel;
        HttpBodyWriter writer = new HttpBodyWriter(0, body, request.trailers(), this::execute, message -> this.writeSocket(socket, message), call.sent());
        this.writeSocket(socket, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
            Throwable failure = writeFailure(done);
            if (failure == null) {
                writer.start();
            } else {
                this.failCall(call, failure);
            }
        }));
    }

    private void written(HttpCall call, Future<?> done) {
        Throwable failure = writeFailure(done);
        if (failure != null) {
            this.finishCall(call, failure);
        } else if (this.draining && this.active == call) {
            this.finishCall(call, new IOException("HTTP connector closing"));
        } else {
            this.finishResponse(call);
        }
    }

    private static Throwable writeFailure(Future<?> done) {
        return done.isCancelled() ? new CancellationException("HTTP write cancelled") : done.getCause();
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

    boolean streamResponse() {
        HttpCall call = this.active;
        if (call == null) {
            return false;
        }
        HttpRequest request = call.request().request();
        return request.stream() != null || this.streamResponseBody();
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
        call.response(response, keepAlive);
        if (response.stream() != null) {
            response.stream().completion().onFinal(done -> this.execute(() -> {
                if (!done.isCancelled() && done.getCause() == null) {
                    this.finishResponse(call);
                } else {
                    this.finishCall(call, done.getCause());
                }
            }));
        }
        call.request().respond(response);
        this.finishResponse(call);
    }

    private void finishResponse(HttpCall call) {
        boolean written = call.sent().isDone() && !call.sent().isCancelled() && call.sent().getCause() == null;
        if (this.active != call || call.response() == null || !written) {
            return;
        }
        HttpBody body = call.response().stream();
        if (body != null && (!body.completion().isDone() || body.completion().getCause() != null)) {
            return;
        }

        // 完整消费响应且上传写完之后，才允许下一个请求复用这条 HTTP/1 连接。
        this.active = null;
        call.request().complete(null);
        if (!call.keepAlive()) {
            this.discardChannel();
        }
        this.next();
    }

    void failed(NetChannel socket, Throwable failure) {
        if (socket != this.channel) {
            return;
        }
        this.channel = null;
        HttpCall call = this.active;
        if (call != null) {
            HttpResponse response = call.response();
            HttpBody body = response == null ? null : response.stream();
            boolean received = response != null && (body == null || body.inputEnded());
            boolean written = call.sent().isDone() && !call.sent().isCancelled() && call.sent().getCause() == null;
            if (received && written) {
                // EOF 之后，已经完整接收的正文仍可由应用读完。
                this.finishResponse(call);
                return;
            }
            this.active = null;
            this.failCall(call, failure);
        }
        this.next();
    }

    private void failCall(HttpCall call, Throwable error) {
        if (!call.markFailed()) {
            return;
        }

        call.request().complete(error);
        this.completeWrite(call.sent(), null, error);
    }
}
