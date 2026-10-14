/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
final class HproseSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange>    connection;
    private final HproseCodec                     protocol;
    private final boolean                         outbound;
    private final BiConsumer<Long, Payload>       messages;
    private final BasicFuture<Void>               ready   = new BasicFuture<>();
    private final Map<Long, HprosePendingRequest> pending = new HashMap<>();

    public HproseSession(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
        this.connection = connection;
        this.protocol = new HproseCodec(config, context.context());
        this.outbound = context.outbound();
        this.messages = messages;
    }

    public Future<Void> ready() {
        return this.ready;
    }

    public void connected() {
        this.ready.completed(null);
    }

    public void closed(Throwable cause) {
        this.ready.failed(cause);
        List<HprosePendingRequest> closing = new ArrayList<>(this.pending.values());
        this.pending.clear();

        for (HprosePendingRequest item : closing) {
            item.batch().sent().failed(cause);
            item.batch().exchange().complete(cause);
        }
    }

    public void receive(HttpExchange exchange) {
        try {
            HproseInvocation invocation = this.protocol.receive(exchange.request());
            if (invocation.immediate() != null) {
                this.reply(exchange, invocation.immediate());
                return;
            }

            // 一个 HTTP 交换持有整个批次；断连或取消必须结束所有子调用。
            HproseBatch batch = new HproseBatch(exchange, invocation);
            exchange.completion().onFinal(done -> this.connection.execute(() -> {
                if (done.getCause() != null) {
                    batch.sent().failed(done.getCause());
                }

                for (HproseCall call : invocation.calls()) {
                    if (call.request() != null) {
                        this.pending.remove(call.request().getRequestID());
                        call.request().complete(done.getCause());
                    }
                }
            }));

            for (int index = 0; index < invocation.calls().size(); index++) {
                HproseCall call = invocation.calls().get(index);
                if (call.failure() != null) {
                    this.accept(batch, index, this.protocol.encodeError(call.failure()));
                    continue;
                }

                RequestPayload request = call.request();
                long id = request.getRequestID();
                HprosePendingRequest item = new HprosePendingRequest(batch, call, index);
                this.pending.put(id, item);
                request.completion().onFinal(done -> this.connection.execute(() -> {
                    if (this.pending.remove(id, item)) {
                        Throwable failure = done.getCause();
                        if (failure == null) {
                            failure = new CancellationException("RPC completed without a response");
                        }
                        this.accept(batch, item.index(), this.protocol.encodeError(failure));
                    }
                }));
                this.messages.accept(id, request);
            }
        } catch (Exception error) {
            this.reply(exchange, this.protocol.error(error));
        }
    }

    private void accept(HproseBatch batch, int index, byte[] response) {
        try {
            if (batch.accept(index, response)) {
                this.bridge(this.connection.write(batch.exchange()), batch.sent());
            }
        } catch (Exception failure) {
            batch.sent().failed(failure);
            batch.exchange().complete(failure);
        }
    }

    public Future<Void> send(Payload payload) {
        BasicFuture<Void> sent = new BasicFuture<>();
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                this.request(request, sent);
            } else if (payload instanceof ResponsePayload response && !this.outbound) {
                this.respond(response, sent);
            } else {
                sent.failed(new UnsupportedOperationException("Hprose channel direction does not support this payload"));
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
            try {
                if (done.getCause() != null) {
                    request.complete(done.getCause());
                    this.messages.accept(request.getRequestID(), new ThrowPayload(done.getCause()));
                    return;
                }

                ResponsePayload response = this.protocol.decode(request, done.getResult());
                this.messages.accept(response.getRequestID(), response);
                request.complete(null);
            } catch (Exception error) {
                request.complete(error);
                this.messages.accept(request.getRequestID(), new ThrowPayload(error));
            }
        }));

        sent.onCancel(done -> {
            request.complete(new CancellationException("HTTP write cancelled"));
        });

        this.bridge(this.connection.write(exchange), sent);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        HprosePendingRequest item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }

        if (!item.call().request().isMessage() && response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }

        this.pending.remove(response.getRequestID());
        byte[] encoded;
        try {
            encoded = this.protocol.encodeResult(item.call(), response);
        } catch (Exception error) {
            encoded = this.protocol.encodeError(error);
        }

        this.accept(item.batch(), item.index(), encoded);
        this.bridge(item.batch().sent(), sent);
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(response)) {
            this.connection.write(exchange);
        }
    }

    private void bridge(Future<Void> writing, BasicFuture<Void> sent) {
        sent.onCancel(done -> writing.cancel());
        writing.onFinal(done -> {
            if (done.isCancelled()) {
                sent.cancel();
            } else {
                if (done.getCause() == null) {
                    sent.completed(null);
                } else {
                    sent.failed(done.getCause());
                }
            }
        });
    }

    public void prepareDrain() {
        // Resolve unanswered HTTP positions before waiting for already submitted responses.
        List<HprosePendingRequest> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (HprosePendingRequest item : unanswered) {
            IOException failure = new IOException("HTTP connector closing");
            this.accept(item.batch(), item.index(), this.protocol.encodeError(failure));
            item.call().request().complete(failure);
        }

        if (this.outbound) {
            this.connection.drainAndClose();
        }
    }
}
