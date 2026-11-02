/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.codec.ThriftMessage;
import net.hasor.meteor.protocol.thrift.codec.ThriftWireCodec;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TMessageType;

/** Native unframed Thrift bodies carried by the shared HTTP exchange transport. */
public final class ThriftHttpSession implements ProtocolSession<HttpExchange> {
    private final RouteMountIndex                  routes;
    private final NetworkChannel<HttpExchange>     channel;
    private final ObjLongConsumer<Payload>         messages;
    private final ThriftCodec                      codec;
    private final ThriftWireCodec                  wire;
    private final boolean                          outbound;
    private final int                              maxMessageSize;
    private final int                              httpVersion;
    private final BasicFuture<Void>                ready   = new BasicFuture<>();
    private final Map<Long, ThriftHttpPendingCall> pending = new HashMap<>();
    private       long                             incomingId;
    private       int                              outgoingSeqid;
    private       Throwable                        closeCause;

    public ThriftHttpSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        this.routes = context.routes();
        this.channel = channel;
        this.messages = messages;
        this.codec = new ThriftCodec(config, context.routes(), context.metContext());
        this.outbound = context.isOutbound();
        this.httpVersion = config.integer("httpVersion", 1);
        this.maxMessageSize = config.integer("maxMessageSize", 8 * 1024 * 1024);
        int maxContainerSize = config.integer("maxContainerSize", 100000);
        int maxDepth = config.integer("maxDepth", 64);
        this.wire = new ThriftWireCodec(this.maxMessageSize, maxContainerSize, maxDepth);
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
        if (this.closeCause != null) {
            exchange.complete(this.closeCause);
            return;
        }

        HttpRequest http = exchange.request();
        RouteMount mount = http.route() == null ? this.routes.match(http.uri()) : http.route().mount();
        if (!StringUtils.equalsIgnoreCase(http.method(), "POST")) {
            this.reply(exchange, 405, Map.of("Allow", List.of("POST")), new byte[0]);
            return;
        }
        if (!this.thriftContentType(http.header("Content-Type"))) {
            this.reply(exchange, 415, Map.of(), new byte[0]);
            return;
        }
        if (mount == null) {
            this.reply(exchange, 404, Map.of(), new byte[0]);
            return;
        }

        ThriftMessage original;
        try {
            byte[] body = http.body();
            if (body.length > this.maxMessageSize) {
                this.reply(exchange, 413, Map.of(), new byte[0]);
                return;
            }
            // HTTP 正文就是一个完整 Thrift 消息，不包含 framed transport 的长度前缀。
            original = this.wire.decode(body);
            byte type = original.messageType();
            if (type != TMessageType.CALL && type != TMessageType.ONEWAY) {
                throw new IOException("Expected a Thrift CALL or ONEWAY request");
            }
        } catch (Exception failure) {
            this.reply(exchange, 400, Map.of(), new byte[0]);
            return;
        }

        try {
            // 当前挂载参与服务选择，不能通过其他目录的同名服务绕过可见性。
            RequestPayload request = this.codec.decodeRequest(original, mount);
            request.setRequestID(++this.incomingId);
            this.copyHeaders(http.headers(), request);
            request.addOption("Location", http.uri());
            ThriftHttpPendingCall call = new ThriftHttpPendingCall(request, exchange, original);
            this.track(call);

            try {
                this.messages.accept(request, request.getRequestID());
            } catch (Exception failure) {
                this.fault(call, failure);
            }
        } catch (TApplicationException failure) {
            this.applicationError(exchange, original, failure);
        } catch (Exception failure) {
            this.reply(exchange, 400, Map.of(), new byte[0]);
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        if (this.closeCause != null) {
            if (payload instanceof RequestPayload request) {
                request.complete(this.closeCause);
            }
            return this.failed(this.closeCause);
        }
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                return this.request(request);
            }
            if (payload instanceof ResponsePayload response && !this.outbound) {
                return this.respond(response);
            }
            throw new IllegalArgumentException("Thrift HTTP channel direction does not support this payload");
        } catch (Exception failure) {
            return this.failed(failure);
        }
    }

    private Future<Void> request(RequestPayload request) throws Exception {
        if (request.completion().isDone()) {
            throw new CancellationException("RPC already completed");
        }
        if (this.pending.containsKey(request.getRequestID())) {
            throw new IllegalArgumentException("Duplicate pending RPC request ID");
        }

        // Thrift 的 int 序号独立分配，不截断内核的 long 请求 ID。
        byte[] body = this.codec.encodeRequest(request, ++this.outgoingSeqid);
        ThriftMessage original = this.wire.decode(body);
        String path = this.codec.requestPath(request);
        Map<String, List<String>> headers = this.headers(request);
        HttpRequest http = HttpRequest.newBuffered("POST", path, headers, body, this.httpVersion, Map.of());
        ThriftHttpPendingCall call = new ThriftHttpPendingCall(request, new HttpExchange(http), original);
        this.track(call);
        call.exchange().response().onFinal(done -> this.channel.execute(() -> {
            if (this.pending.get(request.getRequestID()) != call || request.completion().isDone()) {
                return;
            }

            if (done.getCause() != null) {
                this.fail(call, done.getCause());
                return;
            }

            try {
                HttpResponse response = done.getResult();
                if (response.status() != 200) {
                    throw new IOException("Thrift HTTP status " + response.status());
                }
                ResponsePayload result;
                if (request.isMessage()) {
                    if (response.body().length != 0) {
                        throw new IOException("Thrift ONEWAY HTTP response must have an empty body");
                    }
                    result = new ResponsePayload();
                    result.setRequestID(request.getRequestID());
                    result.setStatus(ProtocolStatus.Accept);
                } else {
                    if (!this.thriftContentType(response.header("Content-Type"))) {
                        throw new IOException("Unexpected Thrift HTTP response Content-Type");
                    }

                    ThriftMessage answer = this.wire.decode(response.body());
                    if (answer.sequenceId() != original.sequenceId()) {
                        throw new IOException("Thrift response sequence ID does not match the request");
                    }
                    if (!StringUtils.equals(answer.methodName(), this.codec.requestMethod(request))) {
                        throw new IOException("Thrift response method does not match the request");
                    }
                    result = this.codec.decodeResponse(request, answer);
                }

                this.copyHeaders(response.headers(), result);
                this.messages.accept(result, request.getRequestID());
                request.complete(null);
            } catch (Throwable failure) {
                this.fail(call, failure);
            }
        }));
        this.write(call);
        return call.sent();
    }

    private Future<Void> respond(ResponsePayload response) {
        ThriftHttpPendingCall call = this.pending.get(response.getRequestID());
        if (call == null) {
            return new BasicFuture<>((Void) null);
        }

        boolean accepted = response.getStatus() == ProtocolStatus.Accept;
        if (accepted && !call.request().isMessage()) {
            return new BasicFuture<>((Void) null);
        }
        if (call.exchange().response().isDone()) {
            return call.sent();
        }

        try {
            if (call.request().isMessage()) {
                // THttpClient.flush 只接受 200；接纳 ONEWAY 后不产生 Thrift Reply。
                this.writeResponse(call, accepted ? 200 : 500, this.headers(response), new byte[0]);
            } else {
                byte[] body = this.codec.encodeResponse(call.request(), response, call.original());
                this.writeResponse(call, 200, this.headers(response), body);
            }
        } catch (Exception failure) {
            this.fault(call, failure);
        }
        return call.sent();
    }

    private void track(ThriftHttpPendingCall call) {
        RequestPayload request = call.request();
        long id = request.getRequestID();
        this.pending.put(id, call);
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

    private void writeResponse(ThriftHttpPendingCall call, int status, Map<String, List<String>> headers, byte[] body) {
        if (call.exchange().respond(HttpResponse.newBuffered(status, headers, body, Map.of()))) {
            this.write(call);
        }
    }

    private void applicationError(HttpExchange exchange, ThriftMessage original, Throwable failure) {
        if (original.messageType() == TMessageType.ONEWAY) {
            // 未进入 Dispatcher 的消息不能伪装成远端已接纳。
            this.reply(exchange, 400, this.headers(null), new byte[0]);
            return;
        }

        try {
            byte[] body = this.codec.encodeError(original, failure);
            this.reply(exchange, 200, this.headers(null), body);
        } catch (Exception encodingFailure) {
            exchange.complete(encodingFailure);
        }
    }

    private void fault(ThriftHttpPendingCall call, Throwable failure) {
        try {
            if (call.request().isMessage()) {
                this.writeResponse(call, 500, this.headers(null), new byte[0]);
            } else {
                byte[] body = this.codec.encodeError(call.original(), failure);
                this.writeResponse(call, 200, this.headers(null), body);
            }
        } catch (Exception encodingFailure) {
            this.fail(call, encodingFailure);
        }
    }

    private void write(ThriftHttpPendingCall call) {
        try {
            Future<Void> writing = this.channel.write(call.exchange());
            call.sent().onCancel(done -> {
                writing.cancel();
                this.channel.execute(() -> this.fail(call, new CancellationException("Thrift HTTP write cancelled")));
            });

            writing.onFinal(done -> this.channel.execute(() -> {
                if (done.isCancelled()) {
                    call.sent().cancel();
                    this.fail(call, new CancellationException("Thrift HTTP write cancelled"));
                } else if (done.getCause() != null) {
                    this.fail(call, done.getCause());
                } else {
                    if (!this.outbound) {
                        this.pending.remove(call.request().getRequestID(), call);
                        call.request().complete(null);
                    } else if (call.request().completion().isDone()) {
                        this.pending.remove(call.request().getRequestID(), call);
                    }
                    call.sent().completed(null);
                }
            }));
        } catch (Exception failure) {
            this.fail(call, failure);
        }
    }

    private void fail(ThriftHttpPendingCall call, Throwable failure) {
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
        if (this.closeCause == null) {
            this.closeCause = cause == null ? new IOException("Thrift HTTP channel closed") : cause;
        }

        this.ready.failed(this.closeCause);
        for (ThriftHttpPendingCall call : new ArrayList<>(this.pending.values())) {
            this.fail(call, this.closeCause);
        }
    }

    @Override
    public void prepareDrain() {
        if (this.closeCause == null) {
            this.closeCause = new IOException("Thrift HTTP connector closing");
        }
        if (this.outbound) {
            return;
        }

        // 仅补齐本会话拥有的交换，借用的物理通道由传输层排空与关闭。
        for (ThriftHttpPendingCall call : new ArrayList<>(this.pending.values())) {
            if (!call.exchange().response().isDone()) {
                this.fault(call, this.closeCause);
            }
        }
    }

    private void reply(HttpExchange exchange, int status, Map<String, List<String>> headers, byte[] body) {
        if (!exchange.respond(HttpResponse.newBuffered(status, headers, body, Map.of()))) {
            return;
        }

        try {
            this.channel.write(exchange).onFinal(done -> {
                if (done.isCancelled()) {
                    exchange.complete(new CancellationException("Thrift HTTP response write cancelled"));
                } else if (done.getCause() != null) {
                    exchange.complete(done.getCause());
                }
            });
        } catch (Exception failure) {
            exchange.complete(failure);
        }
    }

    private boolean thriftContentType(String contentType) {
        String mediaType = contentType == null ? null : contentType.split(";", 2)[0].trim();
        return StringUtils.equalsIgnoreCase(mediaType, "application/x-thrift");
    }

    private Map<String, List<String>> headers(Payload payload) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (payload != null) {
            boolean response = payload instanceof ResponsePayload;
            for (String key : payload.getOptionKeys()) {
                String value = payload.getOption(key);
                boolean contentType = StringUtils.equalsIgnoreCase(key, "Content-Type");
                boolean faultMessage = response && StringUtils.equalsIgnoreCase(key, "message");
                if (key == null || value == null || contentType || faultMessage) {
                    continue;
                }

                this.validateHeader(key, value);
                headers.put(key, List.of(value));
            }
        }
        headers.put("Content-Type", List.of("application/x-thrift"));
        return headers;
    }

    private void validateHeader(String name, String value) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Empty Thrift HTTP metadata name");
        }

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean letter = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            boolean punctuation = "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
            if (!letter && !digit && !punctuation) {
                throw new IllegalArgumentException("Invalid Thrift HTTP metadata name: " + name);
            }
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean text = character >= 32 && character <= 255 && character != 127;
            if (character != '\t' && !text) {
                throw new IllegalArgumentException("Invalid Thrift HTTP metadata value: " + name);
            }
        }
    }

    private Future<Void> failed(Throwable failure) {
        BasicFuture<Void> sent = new BasicFuture<>();
        sent.failed(failure);
        return sent;
    }

    private void copyHeaders(Map<String, List<String>> headers, Payload target) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!header.getValue().isEmpty()) {
                target.addOption(header.getKey(), header.getValue().get(0));
            }
        }
    }
}