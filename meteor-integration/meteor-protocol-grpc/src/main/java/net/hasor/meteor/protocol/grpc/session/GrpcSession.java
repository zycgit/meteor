/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcMethodShape;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
final class GrpcSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange> connection;
    private final GrpcHttpCodec                codec;
    private final ObjLongConsumer<Payload>     messages;
    private final BasicFuture<Void>            ready   = new BasicFuture<>();
    private final Map<Long, Tuple>             pending = new HashMap<>();
    private final Map<Long, GrpcStreamingCall> streams = new HashMap<>();
    private final Set<HttpBodyStream>          bodies  = new HashSet<>();
    private final Executor                     workers;
    private final GrpcMountRuntime             runtime;
    private final ProtocolSessionContext       context;
    private       long                         requestId;
    private       boolean                      closed;
    private       Throwable                    closeCause;

    GrpcSession( //
            ProtocolSessionContext context, NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages, GrpcMountRuntime runtime) {
        this.connection = connection;
        this.context = context;
        this.codec = runtime.codec();
        this.runtime = runtime;
        this.workers = runtime.workers();
        this.messages = messages;
    }

    public Future<Void> ready() {
        return this.ready;
    }

    public void connected() {
        this.ready.completed(null);
    }

    public void closed(Throwable cause) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.closeCause = cause == null ? new IOException("gRPC session closed") : cause;
        this.ready.failed(this.closeCause);
        try {
            for (GrpcStreamingCall call : new ArrayList<>(this.streams.values())) {
                call.fail(this.closeCause);
            }
            for (HttpBodyStream body : new ArrayList<>(this.bodies)) {
                body.fail(this.closeCause);
            }
            this.bodies.clear();
            List<Tuple> closing = new ArrayList<>(this.pending.values());
            this.pending.clear();
            for (Tuple item : closing) {
                RequestPayload request = item.getArg1();
                request.complete(this.closeCause);
            }
        } finally {
            this.runtime.release();
        }
    }

    public void receive(HttpExchange exchange) {
        if (this.closed) {
            this.closeExchange(exchange);
            return;
        }
        if (exchange.request().version() != 2) {
            IOException failure = new IOException("gRPC requires HTTP/2");
            HttpResponse response = HttpResponse.newBuffered(505, Map.of(), new byte[0], Map.of());
            this.reject(exchange, response, failure);
            return;
        }
        String contentType = exchange.request().header("content-type");
        String mediaType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean grpcType = mediaType.matches("application/grpc(?:[+;].*)?");
        if (!grpcType) {
            IOException failure = new IOException("Unsupported gRPC content type");
            HttpResponse response = HttpResponse.newBuffered(415, Map.of(), new byte[0], Map.of());
            this.reject(exchange, response, failure);
            return;
        }
        try {
            RequestPayload request = this.codec.open(exchange.request());
            request.setRequestID(++this.requestId);
            if (new GrpcMethodShape(this.codec.target(request)).streaming()) {
                this.receiveStream(exchange, request);
                return;
            }
            if (exchange.request().stream() != null) {
                // 同一挂载也接收普通 gRPC；聚合只在受限的工作线程中执行。
                this.executeBody(exchange.request().stream(), () -> this.receiveUnaryBody(exchange, request));
                return;
            }
            this.receiveUnary(exchange, exchange.request(), request);
        } catch (Exception error) {
            this.reject(exchange, this.codec.error(error), error);
        }
    }

    private void receiveUnaryBody(HttpExchange exchange, RequestPayload payload) {
        try {
            HttpRequest request = exchange.request();
            HttpBodyStream body = request.stream();
            byte[] bytes = this.readUnaryBody(body);
            HttpRequest complete = HttpRequest.newBuffered(//
                    request.method(), request.uri(), request.headers(), bytes, 2, body.trailers());
            this.connection.execute(() -> {
                if (this.closed) {
                    payload.complete(this.closeCause);
                    this.closeExchange(exchange);
                } else {
                    this.receiveUnary(exchange, complete, payload);
                }
            });
        } catch (Exception failure) {
            this.connection.execute(() -> {
                if (this.closed) {
                    payload.complete(this.closeCause);
                    this.closeExchange(exchange);
                } else {
                    this.reject(exchange, this.codec.error(failure), failure);
                }
            });
        }
    }

    private void receiveUnary(HttpExchange exchange, HttpRequest input, RequestPayload request) {
        if (this.closed) {
            request.complete(this.closeCause);
            this.closeExchange(exchange);
            return;
        }
        try {
            this.codec.readRequest(request, input);
            long id = request.getRequestID();
            Tuple item = Tuple.of(exchange, request);
            this.pending.put(id, item);
            request.completion().onFinal(done -> this.connection.execute(() -> {
                if (this.pending.remove(id, item)) {
                    Throwable failure = done.getCause();
                    if (failure == null) {
                        failure = new CancellationException("RPC completed without a response");
                    }
                    HttpResponse response = this.codec.error(failure);
                    this.reply(exchange, response);
                }
            }));

            exchange.completion().onFinal(done -> request.complete(done.getCause()));
            this.messages.accept(request, id);
        } catch (Exception error) {
            this.reply(exchange, this.codec.error(error));
        }
    }

    public Future<Void> send(Payload payload) {
        BasicFuture<Void> sent = new BasicFuture<>();
        if (this.closed) {
            if (payload instanceof RequestPayload request) {
                request.complete(this.closeCause);
            }
            sent.failed(this.closeCause);
            return sent;
        }
        try {
            if (payload instanceof RequestPayload request && this.context.isOutbound()) {
                if (new GrpcMethodShape(this.codec.target(request)).streaming()) {
                    this.requestStream(request, sent);
                } else {
                    this.request(request, sent);
                }
            } else if (payload instanceof ResponsePayload response && !this.context.isOutbound()) {
                GrpcStreamingCall call = this.streams.get(response.getRequestID());
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

    private GrpcStreamingCall stream(RequestPayload request) {
        GrpcStreamingCall call = new GrpcStreamingCall(//
                request, this.codec, this.context.isOutbound(), this.workers, this.connection::execute, this.messages);
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
        GrpcStreamingCall call = this.stream(request);
        HttpExchange exchange = new HttpExchange(this.codec.streamRequest(request, call.output()));
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
        GrpcStreamingCall call = this.stream(request);
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

        HttpExchange exchange = new HttpExchange(this.codec.encode(request));
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
            // HTTP 回调排队期间 RPC 可能已取消或超时，终结后不再投递迟到结果。
            if (request.completion().isDone()) {
                return;
            }
            if (done.getCause() != null) {
                this.responseFailed(request, done.getCause());
                return;
            }
            if (this.closed) {
                this.responseFailed(request, this.closeCause);
                return;
            }
            HttpResponse response = done.getResult();
            try {
                if (response.stream() == null) {
                    this.decodeResponse(request, response);
                } else {
                    // 普通调用也可能收到增量正文，不能在连接执行器中阻塞读取。
                    this.executeBody(response.stream(), () -> this.receiveUnaryResponse(request, response));
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
        GrpcClientRetryPolicy policy = this.runtime.policy();
        GrpcRetryMethodPolicy method = policy.method(exchange.request().uri());
        if (method == null) {
            return this.connection.write(exchange);
        }
        GrpcClientAttemptCoordinator call = new GrpcClientAttemptCoordinator(//
                exchange, request, this.connection, this.context, this.workers, policy, method);
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

    private void executeBody(HttpBodyStream body, Runnable task) {
        this.bodies.add(body);
        try {
            this.workers.execute(() -> {
                try {
                    task.run();
                } finally {
                    this.connection.execute(() -> this.bodies.remove(body));
                }
            });
        } catch (RuntimeException failure) {
            this.bodies.remove(body);
            throw failure;
        }
    }

    private byte[] readUnaryBody(HttpBodyStream body) throws IOException {
        int messageLimit = this.runtime.messageLimit();
        byte[] bytes = body.readNBytes(messageLimit + 6);
        if (bytes.length > messageLimit + 5) {
            throw new GrpcStatusException(8, "gRPC message exceeds configured maxMessageSize", null, null);
        }
        return bytes;
    }

    private void decodeResponse(RequestPayload request, HttpResponse input) {
        if (this.closed) {
            this.responseFailed(request, this.closeCause);
            return;
        } else if (request.completion().isDone()) {
            return;
        }
        try {
            ResponsePayload response = this.codec.decode(request, input);
            if (request.completion().isDone()) {
                return;
            }
            this.messages.accept(response, response.getRequestID());
            request.complete(null);
        } catch (Exception error) {
            this.responseFailed(request, error);
        }
    }

    private void responseFailed(RequestPayload request, Throwable error) {
        if (request.completion().isDone()) {
            return;
        }

        request.complete(error);
        this.messages.accept(new ThrowPayload(error), request.getRequestID());
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        if (this.closed) {
            sent.failed(this.closeCause);
            return;
        }
        Tuple item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }
        RequestPayload request = item.getArg1();
        if (request.completion().isDone()) {
            Throwable failure = request.completion().getCause();
            if (failure == null) {
                failure = new CancellationException("RPC already completed");
            }
            sent.failed(failure);
            return;
        }
        if (response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }

        this.pending.remove(response.getRequestID(), item);
        HttpResponse encoded;
        try {
            encoded = this.codec.encode(request, response);
        } catch (Exception error) {
            encoded = this.codec.error(error);
        }

        HttpExchange exchange = item.getArg0();
        if (!exchange.respond(this.codec.trailersOnly(encoded))) {
            sent.failed(new IllegalStateException("gRPC HTTP exchange already has a response"));
            return;
        }
        this.bridge(this.connection.write(exchange), sent);
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(this.codec.trailersOnly(response))) {
            this.connection.write(exchange);
        }
    }

    private void reject(HttpExchange exchange, HttpResponse response, Throwable failure) {
        HttpBodyStream body = exchange.request().stream();
        if (body != null) {
            exchange.completion().onCompleted(done -> {
                // 物理响应完成后再释放未消费正文，避免正文失败抢先把 HTTP/2 stream 重置为 CANCEL。
                body.fail(failure);
            });
        }
        this.reply(exchange, response);
    }

    private void closeExchange(HttpExchange exchange) {
        HttpBodyStream body = exchange.request().stream();
        if (body != null) {
            body.fail(this.closeCause);
        }
        exchange.complete(this.closeCause);
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
        for (GrpcStreamingCall call : new ArrayList<>(this.streams.values())) {
            call.fail(new IOException("HTTP connector closing"));
        }
        // Resolve pending protocol exchanges before the transport closes.
        List<Tuple> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (Tuple item : unanswered) {
            this.reply(item.getArg0(), this.codec.error(new IOException("HTTP connector closing")));
        }
    }
}
