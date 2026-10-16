/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.xmlrpc.codec.XmlRpcCodec;

/** Internal session constructed by the protocol entry point; serializes HTTP and RPC lifecycle events. */
public final class XmlRpcSession implements ProtocolSession<HttpExchange> {
    private final ProtocolConfig               config;
    private final NetworkChannel<HttpExchange> channel;
    private final ObjLongConsumer<Payload>     messages;
    private final XmlRpcCodec                  codec;
    private final boolean                      outbound;
    private final BasicFuture<Void>            ready   = new BasicFuture<>();
    private final Map<Long, XmlRpcPendingCall> pending = new HashMap<>();
    private       long                         incomingId;

    public XmlRpcSession(ProtocolConfig config, ProtocolSessionContext context, NetworkChannel<HttpExchange> channel, //
            ObjLongConsumer<Payload> messages) {
        this.config = config;
        this.channel = channel;
        this.messages = messages;
        this.codec = new XmlRpcCodec(config, context.metContext());
        this.outbound = context.isOutbound();
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
    public void receive(HttpExchange exchange) {
        HttpRequest http = exchange.request();
        RouteMount mount = http.route() == null ? this.config.matchContext(http.uri()) : http.route().mount();

        // HTTP 方法、媒体类型与挂载目录先决定是否进入 XML-RPC。
        if (!StringUtils.equalsIgnoreCase(http.method(), "POST")) {
            this.reply(exchange, 405, Map.of("Allow", List.of("POST")), new byte[0]);
            return;
        }
        String contentType = http.header("Content-Type");
        if (contentType != null) {
            String mediaType = contentType.split(";", 2)[0].trim();
            boolean textXml = StringUtils.equalsIgnoreCase(mediaType, "text/xml");
            boolean applicationXml = StringUtils.equalsIgnoreCase(mediaType, "application/xml");
            if (!textXml && !applicationXml) {
                this.reply(exchange, 415, Map.of(), new byte[0]);
                return;
            }
        }
        if (mount == null) {
            this.reply(exchange, 404, Map.of(), new byte[0]);
            return;
        }

        try {
            // XML-RPC 线上没有请求 ID，当前会话分配内部 ID 交给 Dispatcher。
            RequestPayload request = this.codec.decodeRequest(http.body(), mount);
            request.setRequestID(++this.incomingId);
            this.copyHeaders(http.headers(), request);
            request.addOption("Location", http.uri());
            XmlRpcPendingCall call = new XmlRpcPendingCall(request, exchange);
            this.track(call);
            try {
                this.messages.accept(request, request.getRequestID());
            } catch (Exception failure) {
                this.fault(call, failure);
            }
        } catch (Exception failure) {
            try {
                this.reply(exchange, 200, this.xmlHeaders(), this.codec.encodeError(failure));
            } catch (IOException encodingFailure) {
                exchange.complete(encodingFailure);
            }
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                return this.request(request);
            }
            if (payload instanceof ResponsePayload response && !this.outbound) {
                return this.respond(response);
            }
            throw new IllegalArgumentException("XML-RPC channel direction does not support this payload");
        } catch (Exception failure) {
            BasicFuture<Void> sent = new BasicFuture<>();
            sent.failed(failure);
            return sent;
        }
    }

    private Future<Void> request(RequestPayload request) throws Exception {
        if (request.completion().isDone()) {
            throw new CancellationException("RPC already completed");
        }
        if (request.isMessage()) {
            throw new IllegalArgumentException("XML-RPC requires a response and does not support message calls");
        }

        // 编码失败时不建立交换；请求超时仍由调用层统一控制。
        byte[] body = this.codec.encodeRequest(request);
        String path = this.codec.requestPath(request);
        Map<String, List<String>> headers = new LinkedHashMap<>(this.xmlHeaders());
        for (String key : request.getOptionKeys()) {
            String value = request.getOption(key);
            if (key != null && value != null) {
                headers.put(key, List.of(value));
            }
        }
        headers.put("Content-Type", List.of("text/xml; charset=utf-8"));
        int version = this.config.integer("httpVersion", 1);
        HttpRequest wire = HttpRequest.newBuffered("POST", path, headers, body, version, Map.of());
        XmlRpcPendingCall call = new XmlRpcPendingCall(request, new HttpExchange(wire));
        this.track(call);
        call.exchange().response().onFinal(done -> this.channel.execute(() -> {
            if (!this.pending.containsKey(request.getRequestID()) || request.completion().isDone()) {
                return;
            }
            if (done.getCause() != null) {
                this.fail(call, done.getCause());
                return;
            }
            try {
                HttpResponse response = done.getResult();
                if (response.status() != 200) {
                    throw new IOException("XML-RPC HTTP status " + response.status());
                }
                ResponsePayload result = this.codec.decodeResponse(request, response.body());
                this.copyHeaders(response.headers(), result);
                this.messages.accept(result, request.getRequestID());
                request.complete(null);
            } catch (Exception failure) {
                this.fail(call, failure);
            }
        }));
        this.write(call);
        return call.sent();
    }

    private Future<Void> respond(ResponsePayload response) {
        XmlRpcPendingCall call = this.pending.get(response.getRequestID());
        if (call == null || response.getStatus() == ProtocolStatus.Accept) {
            return new BasicFuture<>((Void) null);
        }
        if (call.exchange().response().isDone()) {
            return call.sent();
        }

        byte[] body;
        try {
            body = this.codec.encodeResponse(response);
        } catch (Exception failure) {
            this.fault(call, failure);
            return call.sent();
        }
        this.writeResponse(call, body);
        return call.sent();
    }

    private void track(XmlRpcPendingCall call) {
        RequestPayload request = call.request();
        long id = request.getRequestID();
        this.pending.put(id, call);

        // 调用完成和 HTTP 终止共同释放关联；异步通知回到通道执行器处理。
        request.completion().onFinal(done -> this.channel.execute(() -> {
            if (this.pending.get(id) != call) {
                return;
            }
            Throwable failure = done.getCause();
            if (failure == null) {
                failure = new CancellationException("RPC completed without a response");
            }
            if (this.outbound) {
                boolean answered = done.getCause() == null && call.exchange().response().isDone();
                if (answered) {
                    if (call.sent().isDone()) {
                        this.pending.remove(id);
                    }
                } else {
                    this.pending.remove(id);
                    call.sent().failed(failure);
                    call.exchange().complete(failure);
                }
            } else if (!call.exchange().response().isDone()) {
                this.fault(call, failure);
            }
        }));
        call.exchange().completion().onFinal(done -> this.channel.execute(() -> {
            if (done.getCause() != null) {
                this.fail(call, done.getCause());
            }
        }));
    }

    private void writeResponse(XmlRpcPendingCall call, byte[] body) {
        HttpResponse response = HttpResponse.newBuffered(200, this.xmlHeaders(), body, Map.of());
        if (call.exchange().respond(response)) {
            this.write(call);
        }
    }

    private void fault(XmlRpcPendingCall call, Throwable failure) {
        try {
            this.writeResponse(call, this.codec.encodeError(failure));
        } catch (IOException encodingFailure) {
            this.fail(call, encodingFailure);
        }
    }

    private void write(XmlRpcPendingCall call) {
        try {
            Future<Void> writing = this.channel.write(call.exchange());
            call.sent().onCancel(done -> writing.cancel());
            writing.onFinal(done -> this.channel.execute(() -> {
                if (done.isCancelled()) {
                    call.sent().cancel();
                    this.fail(call, new CancellationException("XML-RPC write cancelled"));
                } else if (done.getCause() != null) {
                    this.fail(call, done.getCause());
                } else {
                    if (!this.outbound) {
                        this.pending.remove(call.request().getRequestID());
                        call.request().complete(null);
                    } else if (call.request().completion().isDone()) {
                        this.pending.remove(call.request().getRequestID());
                    }
                    call.sent().completed(null);
                }
            }));
        } catch (Exception failure) {
            this.fail(call, failure);
        }
    }

    private void fail(XmlRpcPendingCall call, Throwable failure) {
        call.sent().failed(failure);
        long id = call.request().getRequestID();
        if (!this.pending.remove(id, call)) {
            return;
        }
        boolean unfinished = !call.request().completion().isDone();
        call.exchange().complete(failure);
        call.request().complete(failure);
        if (this.outbound && unfinished) {
            this.messages.accept(new ThrowPayload(failure), id);
        }
    }

    @Override
    public void closed(Throwable cause) {
        Throwable failure = cause == null ? new IOException("XML-RPC channel closed") : cause;
        this.ready.failed(failure);
        for (XmlRpcPendingCall call : new ArrayList<>(this.pending.values())) {
            this.fail(call, failure);
        }
    }

    @Override
    public void prepareDrain() {
        // 先补齐尚未回答的位置，物理通道由创建它的传输层负责排空和关闭。
        if (this.outbound) {
            return;
        }
        IOException failure = new IOException("XML-RPC connector closing");
        for (XmlRpcPendingCall call : new ArrayList<>(this.pending.values())) {
            if (!call.exchange().response().isDone()) {
                this.fault(call, failure);
            }
        }
    }

    private void reply(HttpExchange exchange, int status, Map<String, List<String>> headers, byte[] body) {
        HttpResponse response = HttpResponse.newBuffered(status, headers, body, Map.of());
        if (!exchange.respond(response)) {
            return;
        }
        try {
            this.channel.write(exchange).onFinal(done -> {
                if (done.isCancelled()) {
                    exchange.complete(new CancellationException("XML-RPC response write cancelled"));
                } else if (done.getCause() != null) {
                    exchange.complete(done.getCause());
                }
            });
        } catch (Exception failure) {
            exchange.complete(failure);
        }
    }

    private Map<String, List<String>> xmlHeaders() {
        return Map.of("Content-Type", List.of("text/xml; charset=utf-8"));
    }

    private void copyHeaders(Map<String, List<String>> headers, Payload target) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!header.getValue().isEmpty()) {
                target.addOption(header.getKey(), header.getValue().get(0));
            }
        }
    }
}
