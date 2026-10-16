/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.connect.codec.ConnectCors;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcMethodShape;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
public final class ConnectSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange>    connection;
    private final ConnectHttpCodec                codec;
    private final ConnectCors                     cors;
    private final ObjLongConsumer<Payload>        messages;
    private final BasicFuture<Void>               ready   = new BasicFuture<>();
    private final Map<Long, Tuple>                pending = new HashMap<>();
    private final Map<Long, ConnectStreamingCall> streams = new HashMap<>();
    private final Set<HttpBodyStream>             bodies  = new HashSet<>();
    private final Executor                        workers;
    private final ConnectMountRuntime             runtime;
    private final ProtocolSessionContext          context;
    private       long                            requestId;
    private       boolean                         closed;
    private       Throwable                       closeCause;

    /** Internal protocol assembly entry; instances are owned by their connector channel. */
    public ConnectSession( //
            ProtocolSessionContext context, NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages, ConnectMountRuntime runtime) {
        this.connection = connection;
        this.context = context;
        this.codec = runtime.codec();
        this.cors = runtime.cors();
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
        this.closeCause = cause == null ? new IOException("Connect session closed") : cause;
        this.ready.failed(this.closeCause);
        try {
            for (ConnectStreamingCall call : new ArrayList<>(this.streams.values())) {
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
                if (this.context.isOutbound()) {
                    HttpExchange exchange = item.getArg0();
                    Throwable failure = exchange.completion().getCause();
                    this.responseFailed(request, failure == null ? this.closeCause : failure);
                } else {
                    request.complete(this.closeCause);
                }
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
        HttpResponse immediate = this.cors.intercept(exchange.request());
        if (immediate != null) {
            this.reject(exchange, immediate, new IOException("Connect CORS request was handled"));
            return;
        }
        try {
            RequestPayload request = this.codec.open(exchange.request());
            request.setRequestID(++this.requestId);
            GrpcMethodShape shape = new GrpcMethodShape(this.codec.target(request));
            if (shape.streaming()) {
                if (shape.streamingRequest() && shape.streamingResponse() && exchange.request().version() != 2) {
                    throw new UnsupportedOperationException("Bidirectional Connect streaming requires HTTP/2");
                }
                this.receiveStream(exchange, request);
                return;
            }
            if (exchange.request().stream() != null) {
                // 同一挂载也接收普通 Connect；聚合只在受限的工作线程中执行。
                BasicFuture<Void> reading = new BasicFuture<>();
                this.readDeadline(request, reading, failure -> this.connection.execute(() -> {
                    request.complete(failure);
                    this.reject(exchange, this.codec.error(exchange.request(), failure), failure);
                }));
                try {
                    HttpBodyStream body = exchange.request().stream();
                    this.executeBody(body, () -> this.receiveUnaryBody(exchange, request, reading));
                } catch (RuntimeException failure) {
                    reading.completed(null);
                    throw failure;
                }
                return;
            }
            this.receiveUnary(exchange, exchange.request(), request);
        } catch (Exception error) {
            this.reject(exchange, this.codec.error(exchange.request(), error), error);
        }
    }

    private void receiveUnaryBody(HttpExchange exchange, RequestPayload payload, BasicFuture<Void> reading) {
        try {
            HttpRequest request = exchange.request();
            HttpBodyStream body = request.stream();
            byte[] bytes = this.readUnaryBody(body);
            HttpRequest complete = HttpRequest.newBuffered(//
                    request.method(), request.uri(), request.headers(), bytes, request.version(), body.trailers());
            this.connection.execute(() -> {
                if (this.closed) {
                    payload.complete(this.closeCause);
                    this.closeExchange(exchange);
                } else if (reading.completed(null)) {
                    this.receiveUnary(exchange, complete, payload);
                }
            });
        } catch (Exception failure) {
            this.connection.execute(() -> {
                if (this.closed) {
                    payload.complete(this.closeCause);
                    this.closeExchange(exchange);
                } else if (reading.completed(null)) {
                    payload.complete(failure);
                    this.reject(exchange, this.codec.error(exchange.request(), failure), failure);
                }
            });
        }
    }

    private void readDeadline(RequestPayload request, BasicFuture<Void> reading, Consumer<Throwable> failure) {
        long elapsed = System.currentTimeMillis() - request.getReceiveTime();
        long remaining = Math.max(0, request.getClientTimeout() - elapsed);
        Cancellable deadline = this.context.schedule(() -> {
            reading.failed(new MetTimeoutException("Connect request body deadline exceeded"));
        }, remaining);
        reading.onFinal(done -> deadline.cancel());
        reading.onCompleted(done -> {
            // Caller 从接收业务开始计时，因此交付前扣除已消费的正文读取期限。
            long consumed = System.currentTimeMillis() - request.getReceiveTime();
            long timeout = Math.max(1, request.getClientTimeout() - consumed);
            request.setClientTimeout((int) timeout);
        });
        request.completion().onFinal(done -> reading.completed(null));
        reading.onFailed(done -> failure.accept(done.getCause()));
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
                if (this.pending.remove(id, item) && !this.closed) {
                    Throwable failure = done.getCause();
                    if (failure == null) {
                        failure = new CancellationException("RPC completed without a response");
                    }
                    HttpResponse response = this.codec.error(exchange.request(), failure);
                    this.reply(exchange, response);
                }
            }));

            exchange.completion().onFinal(done -> request.complete(done.getCause()));
            this.messages.accept(request, id);
        } catch (Exception error) {
            this.pending.remove(request.getRequestID());
            request.complete(error);
            this.reply(exchange, this.codec.error(exchange.request(), error));
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
                ConnectStreamingCall call = this.streams.get(response.getRequestID());
                if (call == null) {
                    this.respond(response, sent);
                } else if (response.getStatus() == ProtocolStatus.Accept) {
                    sent.completed(null);
                } else {
                    try {
                        call.respond(response);
                    } catch (Exception failure) {
                        call.sendFailure(failure);
                    }
                    this.bridge(call.completion(), sent);
                }
            } else {
                String message = "Connect channel direction does not support this payload";
                sent.failed(new UnsupportedOperationException(message));
            }
        } catch (Exception error) {
            if (payload instanceof RequestPayload request) {
                request.complete(error);
            }
            sent.failed(error);
        }
        return sent;
    }

    private ConnectStreamingCall stream(RequestPayload request, ObjLongConsumer<Payload> delivery) {
        ConnectStreamingCall call = new ConnectStreamingCall(//
                request, this.codec, this.context.isOutbound(), this.workers, this.connection::execute, delivery);
        this.streams.put(request.getRequestID(), call);
        call.completion().onFinal(done -> this.connection.execute(() -> {
            this.streams.remove(request.getRequestID(), call);
        }));
        return call;
    }

    private void requestStream(RequestPayload request, BasicFuture<Void> sent) {
        if (request.isMessage()) {
            throw new IllegalArgumentException("Connect stream calls require a response");
        }
        ConnectStreamingCall call = this.stream(request, this.messages);
        HttpRequest message = this.codec.streamRequest(request, call.output());
        GrpcMethodShape shape = new GrpcMethodShape(this.codec.target(request));
        if (shape.streamingRequest() && shape.streamingResponse() && message.version() != 2) {
            throw new UnsupportedOperationException("Bidirectional Connect streaming requires HTTP/2");
        }
        HttpExchange exchange = new HttpExchange(message);
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
            throw new IllegalArgumentException("Configure streamBody=true on the Connect protocol mount");
        }
        GrpcMethodShape method = new GrpcMethodShape(this.codec.target(request));
        BasicFuture<Void> reading = new BasicFuture<>();
        ConnectStreamingCall call = this.stream(request, (payload, id) -> {
            if (!(payload instanceof RequestPayload) || reading.completed(null)) {
                this.messages.accept(payload, id);
            }
        });
        call.attach(exchange, response -> {
            try {
                HttpResponse decorated = this.cors.decorate(exchange.request(), response);
                if (exchange.respond(decorated)) {
                    call.responseWritten(this.connection.write(exchange));
                } else {
                    call.fail(new IllegalStateException("Connect exchange already has a response"));
                }
            } catch (RuntimeException failure) {
                call.fail(failure);
            }
        });
        if (!method.streamingRequest()) {
            this.readDeadline(request, reading, call::receiveFailure);
        }
        call.receiveRequest();
    }

    private void request(RequestPayload request, BasicFuture<Void> sent) throws Exception {
        if (request.completion().isDone()) {
            sent.failed(new CancellationException("RPC already completed"));
            return;
        }

        HttpExchange exchange = new HttpExchange(this.codec.encode(request));
        long id = request.getRequestID();
        Tuple item = Tuple.of(exchange, request);
        this.pending.put(id, item);
        request.completion().onFinal(done -> this.connection.execute(() -> {
            this.pending.remove(id, item);
            if (!exchange.completion().isDone()) {
                exchange.complete(done.getCause());
            }
        }));

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
        Future<Void> writing = this.connection.write(exchange);
        writing.onFailed(done -> {
            request.complete(done.getCause());
            exchange.complete(done.getCause());
        });
        return writing;
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
        byte[] bytes = body.readNBytes(messageLimit + 1);
        if (bytes.length > messageLimit) {
            throw new IOException("Connect message exceeds configured maxMessageSize");
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
        HttpExchange exchange = item.getArg0();
        HttpResponse encoded;
        try {
            try {
                encoded = this.codec.encode(request, response);
            } catch (Exception error) {
                encoded = this.codec.error(exchange.request(), error);
            }
        } catch (RuntimeException failure) {
            request.complete(failure);
            exchange.complete(failure);
            throw failure;
        }

        if (!exchange.respond(this.cors.decorate(exchange.request(), encoded))) {
            IllegalStateException failure = new IllegalStateException("Connect HTTP exchange already has a response");
            request.complete(failure);
            exchange.complete(failure);
            sent.failed(failure);
            return;
        }
        Future<Void> writing = this.connection.write(exchange);
        writing.onFailed(done -> {
            request.complete(done.getCause());
            exchange.complete(done.getCause());
        });
        this.bridge(writing, sent);
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(this.cors.decorate(exchange.request(), response))) {
            Future<Void> written = this.connection.write(exchange);
            written.onFailed(done -> exchange.complete(done.getCause()));
        }
    }

    private void reject(HttpExchange exchange, HttpResponse response, Throwable failure) {
        HttpBodyStream body = exchange.request().stream();
        if (body != null) {
            exchange.completion().onFinal(done -> {
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
        for (ConnectStreamingCall call : new ArrayList<>(this.streams.values())) {
            call.fail(new IOException("HTTP connector closing"));
        }
        // 入站调用先回送关闭错误；出站调用取消交换，不关闭借用的物理连接。
        List<Tuple> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (Tuple item : unanswered) {
            IOException failure = new IOException("HTTP connector closing");
            RequestPayload request = item.getArg1();
            if (this.context.isOutbound()) {
                this.responseFailed(request, failure);
            } else {
                HttpExchange exchange = item.getArg0();
                this.reply(exchange, this.codec.error(exchange.request(), failure));
            }
        }
    }
}
