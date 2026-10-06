/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.connector.transport.http.HttpExchange;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
final class TestHttpSession implements ProtocolSession<HttpExchange> {

    private final NetworkChannel<HttpExchange>      connection;
    private final TestHttpCodec                     protocol;
    private final boolean                           outbound;
    private final BiConsumer<Long, Payload>         messages;
    private final BasicFuture<Void>                 ready   = new BasicFuture<>();
    private final Map<Long, TestHttpPendingRequest> pending = new HashMap<>();

    public TestHttpSession(TestHttpCodec codec, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
        this.connection = connection;
        this.protocol = codec;
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
        for (TestHttpPendingRequest item : this.pending.values()) {
            item.invocation().request().complete(cause);
        }
        this.pending.clear();
    }

    public void receive(HttpExchange exchange) {
        try {
            TestHttpInvocation invocation = this.protocol.receive(exchange.request());
            if (invocation.immediate() != null) {
                this.reply(exchange, invocation.immediate());
                return;
            }
            RequestPayload request = invocation.request();
            long id = request.getRequestID();
            if (this.pending.containsKey(id)) {
                throw new IllegalStateException("Duplicate HTTP request ID on this channel");
            }
            TestHttpPendingRequest item = new TestHttpPendingRequest(exchange, invocation);
            this.pending.put(id, item);
            request.completion().onFinal(done -> this.connection.execute(() -> {
                if (this.pending.remove(id, item)) {
                    this.reply(exchange, this.protocol.error(done.getCause() != null ? done.getCause() : new CancellationException("RPC completed without a response")));
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
        HttpExchange exchange = new HttpExchange(this.protocol.encode(this.connection.getRemote(), request));
        request.completion().onFinal(done -> {
            if (!exchange.completion().isDone()) {
                exchange.complete(done.getCause() != null ? done.getCause() : new CancellationException("RPC completed"));
            }
        });
        exchange.response().onFinal(done -> this.connection.execute(() -> {
            try {
                if (done.getCause() != null) {
                    request.complete(done.getCause());
                    this.messages.accept(request.getRequestID(), new ThrowPayload(done.getCause()));
                    return;
                }
                ResponsePayload response = this.protocol.decode(request.getRequestID(), done.getResult());
                this.messages.accept(response.getRequestID(), response);
                request.complete(null);
            } catch (Exception error) {
                request.complete(error);
                this.messages.accept(request.getRequestID(), new ThrowPayload(error));
            }
        }));
        sent.onCancel(done -> request.complete(new CancellationException("HTTP write cancelled")));
        this.bridge(this.connection.write(exchange), sent);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        TestHttpPendingRequest item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }
        if (!item.invocation().request().isMessage() && response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }
        this.pending.remove(response.getRequestID());
        HttpResponse encoded;
        try {
            encoded = item.invocation().encoder().encode(response);
        } catch (Exception error) {
            encoded = this.protocol.error(error);
        }
        item.exchange().respond(encoded);
        this.bridge(this.connection.write(item.exchange()), sent);
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
        for (TestHttpPendingRequest item : this.pending.values()) {
            this.reply(item.exchange(), this.protocol.error(new IOException("HTTP connector closing")));
        }
        this.pending.clear();
        if (this.outbound) {
            this.connection.drainAndClose();
        }
    }

}
