/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpBody;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
final class GrpcSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange> connection;
    private final GrpcCodec                    protocol;
    private final boolean                      outbound;
    private final BiConsumer<Long, Payload>    messages;
    private final BasicFuture<Void>            ready   = new BasicFuture<>();
    private final Map<Long, Tuple>             pending = new HashMap<>();
    private final Map<Long, GrpcStreamCall>    streams = new HashMap<>();
    private final ExecutorService              workers;
    private final int                          messageLimit;
    private final ProtocolContext              context;
    private final GrpcClientPolicy             policy;

    public GrpcSession(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages, GrpcClientPolicy policy) {
        this.connection = connection;
        this.context = context;
        this.policy = policy;
        this.protocol = new GrpcCodec(config, context.context());
        this.outbound = context.outbound();
        this.messages = messages;
        this.messageLimit = config.integer("maxMessageSize", 4 * 1024 * 1024);
        this.workers = new ThreadPoolExecutor(0, config.integer("streamWorkers", 64), 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "Meteor-gRPC-Flow");
            thread.setContextClassLoader(context.context().getClassLoader());
            thread.setDaemon(true);
            return thread;
        });
    }

    public Future<Void> ready() {
        return this.ready;
    }

    public void connected() {
        this.ready.completed(null);
    }

    public void closed(Throwable cause) {
        this.ready.failed(cause);
        for (GrpcStreamCall call : new ArrayList<>(this.streams.values())) {
            call.fail(cause);
        }
        this.workers.shutdownNow();
        List<Tuple> closing = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (Tuple item : closing) {
            RequestPayload request = item.getArg1();
            request.complete(cause);
        }
    }

    public void receive(HttpExchange exchange) {
        if (exchange.request().version() != 2) {
            this.reply(exchange, HttpResponse.newBuffered(505, Map.of(), new byte[0], Map.of()));
            return;
        }
        String contentType = exchange.request().header("content-type");
        String mediaType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean grpcType = mediaType.matches("application/grpc(?:[+;].*)?");
        if (!grpcType) {
            this.reply(exchange, HttpResponse.newBuffered(415, Map.of(), new byte[0], Map.of()));
            return;
        }
        try {
            RequestPayload request = this.protocol.open(exchange.request());
            if (new GrpcMethod(this.protocol.target(request)).streaming()) {
                this.receiveStream(exchange, request);
                return;
            }
            if (exchange.request().stream() != null) {
                // 同一挂载也接收普通 gRPC；聚合只在受限的工作线程中执行。
                this.workers.execute(() -> this.receiveUnaryBody(exchange, request));
                return;
            }
            this.receiveUnary(exchange, exchange.request(), request);
        } catch (Exception error) {
            this.reply(exchange, this.protocol.error(error));
        }
    }

    private void receiveUnaryBody(HttpExchange exchange, RequestPayload payload) {
        try {
            HttpRequest request = exchange.request();
            HttpBody body = request.stream();
            byte[] bytes = this.readUnaryBody(body);
            HttpRequest complete = HttpRequest.newBuffered(request.method(), request.uri(), request.headers(), bytes, 2, body.trailers());
            this.connection.execute(() -> this.receiveUnary(exchange, complete, payload));
        } catch (Exception failure) {
            this.connection.execute(() -> this.reply(exchange, this.protocol.error(failure)));
        }
    }

    private void receiveUnary(HttpExchange exchange, HttpRequest input, RequestPayload request) {
        try {
            this.protocol.readRequest(request, input);
            long id = request.getRequestID();
            Tuple item = Tuple.of(exchange, request);
            this.pending.put(id, item);
            request.completion().onFinal(done -> this.connection.execute(() -> {
                if (this.pending.remove(id, item)) {
                    Throwable failure = done.getCause();
                    if (failure == null) {
                        failure = new CancellationException("RPC completed without a response");
                    }
                    HttpResponse response = this.protocol.error(failure);
                    this.reply(exchange, response);
                }
            }));

            exchange.completion().onFinal(done -> request.complete(done.getCause()));
            this.messages.accept(id, request);
        } catch (Exception error) {
            this.reply(exchange, this.protocol.error(error));
        }
    }

    public Future<Void> send(Payload payload) {
        BasicFuture<Void> sent = new BasicFuture<>();
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                if (new GrpcMethod(this.protocol.target(request)).streaming()) {
                    this.requestStream(request, sent);
                } else {
                    this.request(request, sent);
                }
            } else if (payload instanceof ResponsePayload response && !this.outbound) {
                GrpcStreamCall call = this.streams.get(response.getRequestID());
                if (call == null) {
                    this.respond(response, sent);
                } else if (response.getStatus() == ProtocolStatus.Accept) {
                    sent.completed(null);
                } else {
                    call.respond(response);
                    this.bridge(call.completion(), sent);
                }
            } else {
                sent.failed(new UnsupportedOperationException("Grpc channel direction does not support this payload"));
            }
        } catch (Exception error) {
            sent.failed(error);
        }
        return sent;
    }

    private GrpcStreamCall stream(RequestPayload request) {
        GrpcStreamCall call = new GrpcStreamCall(request, this.protocol, this.outbound, this.workers, this.connection::execute, this.messages);
        this.streams.put(request.getRequestID(), call);
        call.completion().onFinal(done -> this.connection.execute(() -> {
            this.streams.remove(request.getRequestID(), call);
        }));
        return call;
    }

    private void requestStream(RequestPayload request, BasicFuture<Void> sent) {
        if (request.isMessage()) {
            throw new IllegalArgumentException("gRPC stream calls require a response");
        }
        GrpcStreamCall call = this.stream(request);
        HttpExchange exchange = new HttpExchange(this.protocol.streamRequest(request, call.output()));
        call.attach(exchange, null);
        exchange.response().onFinal(done -> {
            if (done.getCause() != null) {
                call.fail(done.getCause());
            } else {
                call.receiveResponse(done.getResult());
            }
        });
        sent.onCancel(done -> call.fail(new CancellationException("RPC write cancelled")));
        this.bridge(this.writeRequest(exchange, request), sent);
        call.startRequest();
    }

    private void receiveStream(HttpExchange exchange, RequestPayload request) {
        if (exchange.request().stream() == null) {
            throw new IllegalArgumentException("Configure streamBody=true on the gRPC protocol mount");
        }
        GrpcStreamCall call = this.stream(request);
        call.attach(exchange, response -> {
            if (exchange.respond(response)) {
                call.responseWritten(this.connection.write(exchange));
            }
        });
        call.receiveRequest();
    }

    private void request(RequestPayload request, BasicFuture<Void> sent) throws Exception {
        if (request.completion().isDone()) {
            sent.failed(new CancellationException("RPC already completed"));
            return;
        }

        HttpExchange exchange = new HttpExchange(this.protocol.encode(request));
        request.completion().onFinal(done -> {
            if (!exchange.completion().isDone()) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("RPC completed");
                }
                exchange.complete(failure);
            }
        });

        exchange.response().onFinal(done -> this.connection.execute(() -> {
            if (done.getCause() != null) {
                this.responseFailed(request, done.getCause());
                return;
            }
            HttpResponse response = done.getResult();
            try {
                if (response.stream() == null) {
                    this.decodeResponse(request, response);
                } else {
                    // 普通调用也可能收到增量正文，不能在连接执行器中阻塞读取。
                    this.workers.execute(() -> this.receiveUnaryResponse(request, response));
                }
            } catch (Exception error) {
                this.responseFailed(request, error);
            }
        }));

        sent.onCancel(done -> {
            request.complete(new CancellationException("HTTP write cancelled"));
        });

        this.bridge(this.writeRequest(exchange, request), sent);
    }

    private Future<Void> writeRequest(HttpExchange exchange, RequestPayload request) {
        GrpcMethodPolicy method = this.policy.method(exchange.request().uri());
        if (method == null) {
            return this.connection.write(exchange);
        }
        GrpcClientCall call = new GrpcClientCall(exchange, request, this.connection, this.context, this.workers, this.policy, method);
        return call.start();
    }

    private void receiveUnaryResponse(RequestPayload request, HttpResponse response) {
        try {
            byte[] bytes = this.readUnaryBody(response.stream());
            Map<String, List<String>> trailers = response.stream().trailers();
            HttpResponse complete = HttpResponse.newBuffered(response.status(), response.headers(), bytes, trailers);
            this.connection.execute(() -> this.decodeResponse(request, complete));
        } catch (Exception error) {
            this.connection.execute(() -> this.responseFailed(request, error));
        }
    }

    private byte[] readUnaryBody(HttpBody body) throws IOException {
        byte[] bytes = body.readNBytes(this.messageLimit + 6);
        if (bytes.length > this.messageLimit + 5) {
            throw new GrpcStatusException(8, "gRPC message exceeds configured maxMessageSize", null, null);
        }
        return bytes;
    }

    private void decodeResponse(RequestPayload request, HttpResponse input) {
        try {
            ResponsePayload response = this.protocol.decode(request, input);
            this.messages.accept(response.getRequestID(), response);
            request.complete(null);
        } catch (Exception error) {
            this.responseFailed(request, error);
        }
    }

    private void responseFailed(RequestPayload request, Throwable error) {
        request.complete(error);
        this.messages.accept(request.getRequestID(), new ThrowPayload(error));
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        Tuple item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }
        if (response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }

        this.pending.remove(response.getRequestID());
        HttpResponse encoded;
        try {
            encoded = this.protocol.encode(item.getArg1(), response);
        } catch (Exception error) {
            encoded = this.protocol.error(error);
        }

        HttpExchange exchange = item.getArg0();
        exchange.respond(this.protocol.trailersOnly(encoded));
        this.bridge(this.connection.write(exchange), sent);
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(this.protocol.trailersOnly(response))) {
            this.connection.write(exchange);
        }
    }

    private void bridge(Future<Void> writing, BasicFuture<Void> sent) {
        sent.onCancel(done -> writing.cancel());
        writing.onFinal(done -> {
            if (done.isCancelled()) {
                sent.cancel();
            } else if (done.getCause() == null) {
                sent.completed(null);
            } else {
                sent.failed(done.getCause());
            }
        });
    }

    public void prepareDrain() {
        for (GrpcStreamCall call : new ArrayList<>(this.streams.values())) {
            call.fail(new IOException("HTTP connector closing"));
        }
        // Resolve pending exchanges before draining the HTTP channel.
        List<Tuple> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (Tuple item : unanswered) {
            this.reply(item.getArg0(), this.protocol.error(new IOException("HTTP connector closing")));
        }
        if (this.outbound) {
            this.connection.drainAndClose();
        }
    }
}
