/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
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
import net.hasor.meteor.protocol.soap.codec.SoapInboundResult;
import net.hasor.meteor.protocol.soap.codec.SoapMessageCodec;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionResource;

/** 一个 HTTP 连接上的 SOAP 内部通道；公开构造仅用于协议入口组装。 */
public final class SoapSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange>                     connection;
    private final MetContext                                       context;
    private final SoapMessageCodec                                 codec;
    private final SoapEndpointRouter                               router;
    private final Function<MetBindInfo<?>, SoapDefinitionResource> resources;
    private final boolean                                          outbound;
    private final ObjLongConsumer<Payload>                         messages;
    private final BasicFuture<Void>                                ready   = new BasicFuture<>();
    private final Map<Long, SoapPendingExchange>                   pending = new HashMap<>();
    private       long                                             incomingId;

    public SoapSession(ProtocolConfig config, ProtocolSessionContext context, NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages, SoapMessageCodec codec, //
            Function<MetBindInfo<?>, SoapDefinitionResource> resources) {
        this.connection = connection;
        this.context = context.metContext();
        this.resources = resources;
        this.codec = codec;
        this.outbound = context.isOutbound();
        this.router = this.outbound ? null : new SoapEndpointRouter(//
                config, this.context, context.bindAddress(), this.codec, resources);
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
        Throwable failure = cause == null ? new IOException("SOAP session closed") : cause;
        this.ready.failed(failure);
        List<SoapPendingExchange> closing = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (SoapPendingExchange item : closing) {
            item.request().complete(failure);
            item.exchange().complete(failure);
        }
    }

    @Override
    public void receive(HttpExchange exchange) {
        if (this.outbound) {
            throw new IllegalStateException("Outbound SOAP session cannot receive inbound requests");
        }

        try {
            long incomingRequestId = ++this.incomingId;
            int requestTimeout = this.context.getSettings().getDefaultTimeout();
            SoapInboundResult inbound = this.router.route(exchange.request(), incomingRequestId, requestTimeout);
            if (inbound.response() != null) {
                this.reply(exchange, inbound.response());
                return;
            }

            // 一个 HTTP 交换只对应一个 SOAP operation，并保留到最终响应或 202 回执完成。
            RequestPayload request = inbound.request();
            long requestId = request.getRequestID();
            SoapPendingExchange item = new SoapPendingExchange(exchange, request, inbound.operation(), //
                    inbound.service(), inbound.resource());
            this.pending.put(requestId, item);
            request.completion().onFinal(done -> this.connection.execute(() -> {
                if (!this.pending.remove(requestId, item)) {
                    return;
                }
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("SOAP call completed without a response");
                }
                this.reply(exchange, this.codec.error(failure));
            }));
            exchange.completion().onFinal(done -> this.connection.execute(() -> {
                this.pending.remove(requestId, item);
                request.complete(done.getCause());
            }));
            this.messages.accept(request, requestId);
        } catch (Exception failure) {
            this.reply(exchange, this.codec.error(failure));
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
                sent.failed(new UnsupportedOperationException("SOAP channel direction does not support this payload"));
            }
        } catch (Exception failure) {
            sent.failed(failure);
        }
        return sent;
    }

    private void request(RequestPayload request, BasicFuture<Void> sent) throws Exception {
        if (request.completion().isDone()) {
            sent.failed(new CancellationException("RPC already completed"));
            return;
        }

        MetBindInfo<?> service = this.service(request);
        SoapDefinitionResource resource = this.resources.apply(service);
        HttpExchange exchange = new HttpExchange(this.codec.encode(request, service, resource));
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
            // HTTP 回调排队期间 RPC 可能已取消或超时，终结后不再投递迟到响应。
            if (request.completion().isDone()) {
                return;
            }
            if (done.getCause() != null) {
                this.responseFailed(request, done.getCause());
                return;
            }

            try {
                ResponsePayload response = this.codec.decode(request, done.getResult(), service, resource);
                this.messages.accept(response, response.getRequestID());
                request.complete(null);
            } catch (Exception failure) {
                this.responseFailed(request, failure);
            }
        }));
        sent.onCancel(done -> request.complete(new CancellationException("HTTP write cancelled")));
        this.bridge(this.connection.write(exchange), sent);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        SoapPendingExchange item = this.pending.get(response.getRequestID());
        if (item == null) {
            sent.failed(new IllegalStateException("SOAP response has no pending request on this channel"));
            return;
        }
        if (item.request().completion().isDone()) {
            sent.failed(new CancellationException("SOAP request already completed"));
            return;
        }

        // 普通调用的 Accept 只表示入队；one-way 的 Accept 是线上唯一响应，映射为 HTTP 202。
        if (response.getStatus() == ProtocolStatus.Accept) {
            if (!item.operation().oneWay()) {
                sent.completed(null);
                return;
            }

            this.pending.remove(response.getRequestID(), item);
            this.write(item.exchange(), this.codec.accepted(), sent);
            return;
        }

        this.pending.remove(response.getRequestID(), item);
        HttpResponse encoded;
        try {
            encoded = this.codec.encode(item.exchange(), item.request(), item.operation(), response, //
                    item.service(), item.resource());
        } catch (Exception failure) {
            encoded = this.codec.error(failure);
        }
        this.write(item.exchange(), encoded, sent);
    }

    private void write(HttpExchange exchange, HttpResponse response, BasicFuture<Void> sent) {
        if (!exchange.respond(response)) {
            sent.failed(new IllegalStateException("SOAP HTTP exchange already has a response"));
            return;
        }
        this.bridge(this.connection.write(exchange), sent);
    }

    private void responseFailed(RequestPayload request, Throwable failure) {
        request.complete(failure);
        this.messages.accept(new ThrowPayload(failure), request.getRequestID());
    }

    private MetBindInfo<?> service(RequestPayload request) {
        MetBindInfo<?> service = this.context.getServiceInfo(request.getServiceGroup(), request.getServiceName(), //
                request.getServiceVersion());
        if (service == null) {
            throw new IllegalArgumentException("SOAP service is not registered");
        }
        return service;
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (exchange.respond(response)) {
            this.connection.write(exchange).onFinal(done -> {
                Throwable failure = done.getCause();
                if (done.isCancelled() && failure == null) {
                    failure = new CancellationException("SOAP HTTP reply write cancelled");
                }
                if (failure != null) {
                    exchange.complete(failure);
                }
            });
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

    @Override
    public void prepareDrain() {
        List<SoapPendingExchange> unanswered = new ArrayList<>(this.pending.values());
        this.pending.clear();
        for (SoapPendingExchange item : unanswered) {
            IOException failure = new IOException("HTTP connector closing");
            this.reply(item.exchange(), this.codec.error(failure));
            item.request().complete(failure);
        }
    }
}
