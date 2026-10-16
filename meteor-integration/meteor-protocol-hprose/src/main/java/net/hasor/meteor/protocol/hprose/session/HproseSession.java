/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.hprose.codec.HproseDecodedCall;
import net.hasor.meteor.protocol.hprose.codec.HproseDecodedInvocation;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodec;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
public final class HproseSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange> channel;
    private final HproseHttpCodec              codec;
    private final boolean                      outbound;
    private final ObjLongConsumer<Payload>     messages;
    private final BasicFuture<Void>            ready   = new BasicFuture<>();
    private final Map<Long, HprosePendingCall> pending = new HashMap<>();
    private       long                         incomingId;

    /** Internal protocol assembly entry; instances are owned by their connector channel. */
    public HproseSession( //
            ProtocolSessionContext context, NetworkChannel<HttpExchange> channel, //
            ObjLongConsumer<Payload> messages, HproseHttpCodec codec) {
        this.channel = channel;
        this.codec = codec;
        this.outbound = context.isOutbound();
        this.messages = messages;
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        this.ready.completed(null);
    }

    @Override
    public void closed(Throwable cause) {
        this.ready.failed(cause);
        List<HprosePendingCall> closing = new ArrayList<>(this.pending.values());
        this.pending.clear();

        for (HprosePendingCall item : closing) {
            item.batch().sent().failed(cause);
            item.batch().exchange().complete(cause);
        }
    }

    @Override
    public void receive(HttpExchange exchange) {
        try {
            HproseDecodedInvocation invocation = this.codec.decodeRequest(exchange.request());
            if (invocation.immediate() != null) {
                this.reply(exchange, invocation.immediate());
                return;
            }

            // 一个 HTTP 交换持有整个批次；断连或取消必须结束所有子调用。
            HproseResponseBatch batch = new HproseResponseBatch(exchange, invocation.calls().size());
            exchange.completion().onFinal(done -> this.channel.execute(() -> {
                if (done.getCause() != null) {
                    batch.sent().failed(done.getCause());
                }

                for (HproseDecodedCall call : invocation.calls()) {
                    if (call.request() != null) {
                        this.pending.remove(call.request().getRequestID());
                        call.request().complete(done.getCause());
                    }
                }
            }));

            for (int index = 0; index < invocation.calls().size(); index++) {
                HproseDecodedCall call = invocation.calls().get(index);
                if (call.failure() != null) {
                    this.complete(batch, index, this.codec.encodeErrorPart(call.failure()));
                    continue;
                }

                RequestPayload request = call.request();
                request.setRequestID(++this.incomingId);
                long id = request.getRequestID();
                HprosePendingCall item = new HprosePendingCall(batch, call, index);
                this.pending.put(id, item);
                request.completion().onFinal(done -> this.channel.execute(() -> {
                    if (this.pending.remove(id, item)) {
                        Throwable failure = done.getCause();
                        if (failure == null) {
                            failure = new CancellationException("RPC completed without a response");
                        }
                        this.complete(batch, item.index(), this.codec.encodeErrorPart(failure));
                    }
                }));
                this.messages.accept(request, id);
            }
        } catch (Exception error) {
            this.reply(exchange, this.codec.encodeErrorResponse(exchange.request(), error));
        }
    }

    private void complete(HproseResponseBatch batch, int index, byte[] response) {
        try {
            if (batch.complete(index, response)) {
                HttpResponse result = this.codec.encodeBatchResponse(batch.exchange().request(), batch.responses());
                if (batch.exchange().respond(result)) {
                    this.bridge(this.channel.write(batch.exchange()), batch.sent());
                }
            }
        } catch (Exception failure) {
            batch.sent().failed(failure);
            batch.exchange().complete(failure);
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        BasicFuture<Void> sent = new BasicFuture<>();
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                this.request(request, sent);
            } else if (payload instanceof ResponsePayload response && !this.outbound) {
                this.respond(response, sent);
            } else {
                UnsupportedOperationException failure = new UnsupportedOperationException(//
                        "Hprose channel direction does not support this payload");
                sent.failed(failure);
            }
        } catch (Exception error) {
            sent.failed(error);
        }
        return sent;
    }

    private void request(RequestPayload request, BasicFuture<Void> sent) throws Exception {
        if (request.completion().isDone()) {
            sent.failed(new CancellationException("RPC already completed"));
            return;
        }

        HttpExchange exchange = new HttpExchange(this.codec.encodeRequest(request));
        request.completion().onFinal(done -> {
            if (!exchange.completion().isDone()) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("RPC completed");
                }
                exchange.complete(failure);
            }
        });

        exchange.response().onFinal(done -> this.channel.execute(() -> {
            // HTTP 回调排队期间 RPC 可能已取消或超时，终结后不再投递迟到响应。
            if (request.completion().isDone()) {
                return;
            }
            try {
                if (done.getCause() != null) {
                    request.complete(done.getCause());
                    this.messages.accept(new ThrowPayload(done.getCause()), request.getRequestID());
                    return;
                }

                ResponsePayload response = this.codec.decodeResponse(request, done.getResult());
                this.messages.accept(response, response.getRequestID());
                request.complete(null);
            } catch (Exception error) {
                request.complete(error);
                this.messages.accept(new ThrowPayload(error), request.getRequestID());
            }
        }));

        sent.onCancel(done -> {
            request.complete(new CancellationException("HTTP write cancelled"));
        });

        this.bridge(this.channel.write(exchange), sent);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        HprosePendingCall item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }

        RequestPayload request = item.call().request();
        if (request.completion().isDone()) {
            Throwable failure = request.completion().getCause();
            if (failure == null) {
                failure = new CancellationException("RPC already completed");
            }
            sent.failed(failure);
            return;
        }

        if (!request.isMessage() && response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }

        this.pending.remove(response.getRequestID());
        byte[] encoded;
        try {
            encoded = this.codec.encodeResponse(item.call(), response);
        } catch (Exception error) {
            encoded = this.codec.encodeErrorPart(error);
        }

        this.complete(item.batch(), item.index(), encoded);
        this.observe(item.batch().sent(), sent);
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(response)) {
            this.channel.write(exchange);
        }
    }

    private void bridge(Future<Void> writing, BasicFuture<Void> sent) {
        sent.onCancel(done -> writing.cancel());
        this.observe(writing, sent);
    }

    private void observe(Future<Void> source, BasicFuture<Void> sent) {
        source.onFinal(done -> {
            if (done.isCancelled()) {
                sent.cancel();
            } else if (done.getCause() == null) {
                sent.completed(null);
            } else {
                sent.failed(done.getCause());
            }
        });
    }

    @Override
    public void prepareDrain() {
        // Resolve unanswered HTTP positions before waiting for already submitted responses.
        List<HprosePendingCall> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (HprosePendingCall item : unanswered) {
            IOException failure = new IOException("HTTP connector closing");
            this.complete(item.batch(), item.index(), this.codec.encodeErrorPart(failure));
            item.call().request().complete(failure);
        }
    }
}
