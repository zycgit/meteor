/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.triple.codec.TripleHttpCodec;
import net.hasor.meteor.protocol.triple.codec.TripleMethodShape;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
public final class TripleSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange>   connection;
    private final TripleHttpCodec                codec;
    private final ObjLongConsumer<Payload>       messages;
    private final BasicFuture<Void>              ready   = new BasicFuture<>();
    private final Map<Long, Tuple>               pending = new HashMap<>();
    private final Map<Long, TripleStreamingCall> streams = new HashMap<>();
    private final Set<HttpBodyStream>            bodies  = new HashSet<>();
    private final Executor                       workers;
    private final TripleMountRuntime             runtime;
    private final ProtocolSessionContext         context;
    private       long                           requestId;
    private       boolean                        closed;
    private       Throwable                      closeCause;

    /** Internal protocol assembly entry; instances are owned by their connector channel. */
    public TripleSession( //
            ProtocolSessionContext context, NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages, TripleMountRuntime runtime) {
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
        this.closeCause = cause == null ? new IOException("Triple session closed") : cause;
        this.ready.failed(this.closeCause);
        Map<RequestPayload, HttpBodyStream> received = new HashMap<>();
        try {
            // 已完整接收的普通响应继续解码，其正文不能因物理连接关闭而被丢弃。
            List<Tuple> closing = new ArrayList<>(this.pending.values());
            if (this.context.isOutbound()) {
                for (Tuple item : closing) {
                    HttpResponse response = this.completeResponse(item.getArg0());
                    if (response != null) {
                        received.put(item.getArg1(), response.stream());
                    }
                }
            }

            for (TripleStreamingCall call : new ArrayList<>(this.streams.values())) {
                call.fail(this.closeCause);
            }

            for (HttpBodyStream body : new ArrayList<>(this.bodies)) {
                if (!received.containsValue(body)) {
                    body.fail(this.closeCause);
                }
            }

            this.bodies.clear();
            this.pending.clear();
            for (Tuple item : closing) {
                RequestPayload request = item.getArg1();
                if (received.containsKey(request)) {
                    continue;
                } else if (this.context.isOutbound()) {
                    HttpExchange exchange = item.getArg0();
                    Throwable failure = exchange.completion().getCause();
                    this.responseFailed(request, failure == null ? this.closeCause : failure);
                } else {
                    request.complete(this.closeCause);
                }
            }
        } finally {
            // 完整响应仍受 Caller 的取消和期限约束，最后一条终结后归还本会话的 worker 租约。
            if (received.isEmpty()) {
                this.runtime.release();
            } else {
                AtomicInteger remaining = new AtomicInteger(received.size());
                for (RequestPayload request : received.keySet()) {
                    request.completion().onFinal(done -> {
                        if (remaining.decrementAndGet() == 0) {
                            this.runtime.release();
                        }
                    });
                }
            }
        }
    }

    private HttpResponse completeResponse(HttpExchange exchange) {
        Future<HttpResponse> response = exchange.response();
        if (!response.isDone() || response.getCause() != null || exchange.completion().getCause() != null) {
            return null;
        }

        HttpResponse complete = response.getResult();
        HttpBodyStream body = complete.stream();
        if (body != null && (!body.inputEnded() || body.completion().getCause() != null)) {
            return null;
        }
        return complete;
    }

    public void receive(HttpExchange exchange) {
        if (this.closed) {
            this.closeExchange(exchange);
            return;
        }

        try {
            RequestPayload request = this.codec.open(exchange.request());
            request.setRequestID(++this.requestId);
            HttpRequest unaryInput = exchange.request();
            boolean resolve = this.codec.needsMethodResolution(request);
            if (resolve && unaryInput.stream() != null) {
                this.resolveRequest(exchange, request);
                return;
            }

            if (resolve) {
                this.codec.readRequest(request, unaryInput);
                unaryInput = null;
            }
            TripleMethodShape shape = this.codec.shape(request);
            if (shape.streaming()) {
                this.receiveStream(exchange, request, null, null);
                return;
            }
            if (exchange.request().stream() != null) {
                // 同一挂载也接收普通 Triple；聚合只在受限的工作线程中执行。
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
            this.receiveUnary(exchange, unaryInput, request);
        } catch (Exception error) {
            this.reject(exchange, this.codec.error(exchange.request(), error), error);
        }
    }

    private void resolveRequest(HttpExchange exchange, RequestPayload request) {
        BasicFuture<Void> reading = new BasicFuture<>();
        this.readDeadline(request, reading, failure -> this.connection.execute(() -> {
            request.complete(failure);
            this.reject(exchange, this.codec.error(exchange.request(), failure), failure);
        }));

        HttpBodyStream body = exchange.request().stream();
        try {
            this.executeBody(body, () -> this.readFirstRequest(exchange, request, reading));
        } catch (RuntimeException failure) {
            reading.completed(null);
            throw failure;
        }
    }

    private void readFirstRequest(HttpExchange exchange, RequestPayload request, BasicFuture<Void> reading) {
        try {
            // 包装消息携带参数类型；先解析首帧，再决定同名重载的普通或 Flow 链路。
            HttpRequest input = exchange.request();
            byte[] first = this.codec.readMessage(input.stream(), input.header("grpc-encoding"));
            this.codec.resolveRequestMethod(request, first);
            TripleMethodShape shape = this.codec.shape(request);
            if (shape.streaming()) {
                this.connection.execute(() -> {
                    if (this.closed) {
                        this.closeExchange(exchange);
                    } else if (!reading.isDone()) {
                        this.receiveStream(exchange, request, first, reading);
                    }
                });
                return;
            }

            byte[] extra = this.codec.readMessage(input.stream(), input.header("grpc-encoding"));
            if (first == null || extra != null) {
                throw new IOException("Triple method requires exactly one request message");
            }
            this.codec.decodeRequestValue(request, first);
            this.connection.execute(() -> {
                if (this.closed) {
                    this.closeExchange(exchange);
                } else if (reading.completed(null)) {
                    this.receiveUnary(exchange, null, request);
                }
            });
        } catch (Exception failure) {
            this.connection.execute(() -> {
                if (reading.completed(null) && !this.closed) {
                    request.complete(failure);
                    this.reject(exchange, this.codec.error(exchange.request(), failure), failure);
                }
            });
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
            reading.failed(new MetTimeoutException("Triple request body deadline exceeded"));
        }, remaining);
        reading.onFinal(done -> {
            deadline.cancel();
        });
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
            if (input != null) {
                this.codec.readRequest(request, input);
            }

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
                if (request.isMessage()) {
                    throw new IllegalArgumentException("Triple calls require a response");
                }
                if (this.codec.shape(request).streaming()) {
                    this.requestStream(request, sent);
                } else {
                    this.request(request, sent);
                }
            } else if (payload instanceof ResponsePayload response && !this.context.isOutbound()) {
                TripleStreamingCall call = this.streams.get(response.getRequestID());
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
                String message = "Triple channel direction does not support this payload";
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

    private TripleStreamingCall stream(RequestPayload request, ObjLongConsumer<Payload> delivery) {
        TripleStreamingCall call = new TripleStreamingCall(//
                request, this.codec, this.context.isOutbound(), this.workers, this.connection::execute, delivery);
        this.streams.put(request.getRequestID(), call);
        call.completion().onFinal(done -> this.connection.execute(() -> {
            this.streams.remove(request.getRequestID(), call);
        }));
        return call;
    }

    private void requestStream(RequestPayload request, BasicFuture<Void> sent) {
        if (request.completion().isDone()) {
            throw new CancellationException("RPC already completed");
        }

        TripleStreamingCall call = this.stream(request, this.messages);
        HttpRequest message = this.codec.streamRequest(request, call.output());
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

    private void receiveStream(HttpExchange exchange, RequestPayload request, byte[] firstMessage, BasicFuture<Void> prefetched) {
        if (exchange.request().stream() == null) {
            throw new IllegalArgumentException("Configure streamBody=true on the Triple protocol mount");
        }

        TripleMethodShape method = this.codec.shape(request);
        BasicFuture<Void> reading = prefetched == null ? new BasicFuture<>() : prefetched;
        TripleStreamingCall call = this.stream(request, (payload, id) -> {
            if (!(payload instanceof RequestPayload) || reading.completed(null)) {
                this.messages.accept(payload, id);
            }
        });

        call.attach(exchange, response -> {
            try {
                if (exchange.respond(response)) {
                    call.responseWritten(this.connection.write(exchange));
                } else {
                    call.fail(new IllegalStateException("Triple exchange already has a response"));
                }
            } catch (RuntimeException failure) {
                call.fail(failure);
            }
        });

        if (prefetched == null && !method.streamingRequest()) {
            this.readDeadline(request, reading, call::receiveFailure);
        }
        call.receiveRequest(firstMessage);
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

            if (this.closed && this.completeResponse(exchange) == null) {
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
        int messageLimit = this.runtime.bodyLimit();
        byte[] bytes = body.readNBytes(messageLimit + 1);
        if (bytes.length > messageLimit) {
            throw new GrpcStatusException(8, "Triple message exceeds configured maxMessageSize", null, null);
        }
        return bytes;
    }

    private void decodeResponse(RequestPayload request, HttpResponse input) {
        if (request.completion().isDone()) {
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

        if (!exchange.respond(encoded)) {
            IllegalStateException failure = new IllegalStateException("Triple HTTP exchange already has a response");
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
        if (exchange.respond(response)) {
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
        for (TripleStreamingCall call : new ArrayList<>(this.streams.values())) {
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
