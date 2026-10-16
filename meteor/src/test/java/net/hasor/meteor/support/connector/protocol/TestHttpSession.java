/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/**
 * Maps RPC requests to HTTP exchanges; the underlying channel owns HTTP wire ordering.
 */
public final class TestHttpSession implements ProtocolSession<HttpExchange> {

    private final NetworkChannel<HttpExchange>      connection;
    private final TestHttpCodec                     protocol;
    private final boolean                           outbound;
    private final ObjLongConsumer<Payload>          messages;
    private final BasicFuture<Void>                 ready   = new BasicFuture<>();
    private final Map<Long, TestHttpPendingRequest> pending = new HashMap<>();

    public TestHttpSession( //
            TestHttpCodec codec, //
            ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages) {
        this.connection = connection;
        this.protocol = codec;
        this.outbound = context.isOutbound();
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
        // 通知尚未完成的调用后清理当前连接的关联状态。
        for (TestHttpPendingRequest item : this.pending.values()) {
            item.invocation().request().complete(cause);
        }
        this.pending.clear();
    }

    public void receive(HttpExchange exchange) {
        // 元数据等即时响应不进入 RPC 待完成表。
        try {
            TestHttpInvocation invocation = this.protocol.receive(exchange.request());
            if (invocation.immediate() != null) {
                this.reply(exchange, invocation.immediate());
                return;
            }

            // 每个连接独立跟踪请求，完成通知负责释放尚未发送的响应位置。
            RequestPayload request = invocation.request();
            long id = request.getRequestID();
            if (this.pending.containsKey(id)) {
                throw new IllegalStateException("Duplicate HTTP request ID on this channel");
            }
            TestHttpPendingRequest item = new TestHttpPendingRequest(exchange, invocation);
            this.pending.put(id, item);
            request.completion().onFinal(done -> {
                this.connection.execute(() -> {
                    if (this.pending.remove(id, item)) {
                        Throwable failure = done.getCause();
                        if (failure == null) {
                            failure = new CancellationException("RPC completed without a response");
                        }
                        HttpResponse response = this.protocol.error(failure);
                        this.reply(exchange, response);
                    }
                });
            });
            exchange.completion().onFinal(done -> request.complete(done.getCause()));
            this.messages.accept(request, id);
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
                sent.failed(new UnsupportedOperationException("HTTP channel direction does not support this payload"));
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

        // 编码后关联 HTTP 交换和 RPC 生命周期，取消时释放传输队列。
        HttpRequest encoded = this.protocol.encode(this.connection.getRemote(), request);
        HttpExchange exchange = new HttpExchange(encoded);
        request.completion().onFinal(done -> {
            if (!exchange.completion().isDone()) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("RPC completed");
                }
                exchange.complete(failure);
            }
        });

        // 网络响应回到连接线程后再解码并通知调用方。
        exchange.response().onFinal(done -> {
            this.connection.execute(() -> {
                try {
                    if (done.getCause() != null) {
                        request.complete(done.getCause());
                        this.messages.accept(new ThrowPayload(done.getCause()), request.getRequestID());
                        return;
                    }
                    ResponsePayload response = this.protocol.decode(request.getRequestID(), done.getResult());
                    this.messages.accept(response, response.getRequestID());
                    request.complete(null);
                } catch (Exception error) {
                    request.complete(error);
                    this.messages.accept(new ThrowPayload(error), request.getRequestID());
                }
            });
        });
        sent.onCancel(done -> request.complete(new CancellationException("HTTP write cancelled")));
        this.bridge(this.connection.write(exchange), sent);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        // 普通调用的接受确认不占用最终响应位置；消息确认可直接完成。
        TestHttpPendingRequest item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("HTTP response has no pending request on this channel"));
            return;
        }
        RequestPayload pendingRequest = item.invocation().request();
        boolean ordinaryAcknowledgement = !pendingRequest.isMessage() && response.getStatus() == ProtocolStatus.Accept;
        if (ordinaryAcknowledgement) {
            sent.completed(null);
            return;
        }

        // 响应编码失败仍返回 HTTP 错误，避免阻塞后续流水线位置。
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
        // 发送取消向下传递，写入完成结果向上传递。
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
        // 先补齐未答复位置，再等待已提交响应排空。
        for (TestHttpPendingRequest item : this.pending.values()) {
            IOException failure = new IOException("HTTP connector closing");
            HttpResponse response = this.protocol.error(failure);
            this.reply(item.exchange(), response);
        }
        this.pending.clear();
        if (this.outbound) {
            this.connection.drainAndClose();
        }
    }

}
