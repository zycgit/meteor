/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketHandshakeUtils;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPayload;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcCall;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcCodec;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcEnvelope;

/** Correlates JSON-RPC calls with Dispatcher requests on the ordered network executor. */
public final class JsonRpcSession implements ProtocolSession<HttpExchange> {
    private final ProtocolConfig                         config;
    private final NetworkChannel<HttpExchange>           http;
    private final ObjLongConsumer<Payload>               messages;
    private final JsonRpcCodec                           codec;
    private final boolean                                outbound;
    private final boolean                                upgrade;
    private final BasicFuture<Void>                      ready    = new BasicFuture<>();
    private final Map<Long, JsonRpcPendingCall>          incoming = new HashMap<>();
    private final Map<Long, RequestPayload>              outgoing = new HashMap<>();
    private final List<NetworkChannel<WebSocketPayload>> sockets  = new ArrayList<>();
    private final Set<JsonRpcResponseBatch>              batches  = new HashSet<>();
    private       long                                   incomingId;

    /** Internal protocol assembly entry; instances are owned by their connector channel. */
    public JsonRpcSession(ProtocolConfig config, ProtocolSessionContext context, NetworkChannel<HttpExchange> http, //
            ObjLongConsumer<Payload> messages) {
        this.config = config;
        this.http = http;
        this.messages = messages;
        this.outbound = context.isOutbound();
        this.upgrade = StringUtils.equalsIgnoreCase(config.option("upgrade", ""), "websocket");
        this.codec = new JsonRpcCodec(config, context.metContext());
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        if (!this.upgrade || !this.outbound) {
            this.ready.completed(null);
        } else {
            // WebSocket 在配置的目录完成握手，之后每条消息沿用该目录的可见性。
            int version = this.config.integer("httpVersion", 1);
            HttpExchange exchange = WebSocketHandshakeUtils.newExchange(this.config.contextPath(), version);
            this.attach(exchange, null);
            this.http.write(exchange).onFinal(done -> {
                if (done.isCancelled()) {
                    this.ready.cancel();
                } else if (done.getCause() != null) {
                    this.ready.failed(done.getCause());
                }
            });
        }
    }

    @Override
    public void receive(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        RouteMount mount = request.route() == null ? this.config.matchContext(request.uri()) : request.route().mount();
        boolean get = StringUtils.equalsIgnoreCase(request.method(), "GET");
        boolean connect = StringUtils.equalsIgnoreCase(request.method(), "CONNECT");
        boolean http1Upgrade = get && StringUtils.equalsIgnoreCase(request.header("Upgrade"), "websocket");
        boolean http2Connect = connect && StringUtils.equalsIgnoreCase(request.header(":protocol"), "websocket");
        if (this.upgrade && (http1Upgrade || http2Connect)) {
            this.attach(exchange, mount);
            return;
        }

        // HTTP 只承载 POST 文档；方法和媒体类型错误不进入 Dispatcher。
        if (!StringUtils.equalsIgnoreCase(request.method(), "POST")) {
            this.reply(exchange, 405, Map.of("Allow", List.of("POST")), new byte[0]);
            return;
        }
        String contentType = request.header("Content-Type");
        if (contentType != null) {
            String mediaType = contentType.split(";", 2)[0].trim();
            boolean json = StringUtils.equalsIgnoreCase(mediaType, "application/json");
            boolean jsonRpc = StringUtils.equalsIgnoreCase(mediaType, "application/json-rpc");
            if (!json && !jsonRpc) {
                this.reply(exchange, 415, Map.of(), new byte[0]);
                return;
            }
        }
        if (mount == null) {
            this.reply(exchange, 404, Map.of(), new byte[0]);
            return;
        }

        JsonRpcEnvelope envelope = this.codec.decodeRequest(request.body(), mount);
        JsonRpcResponseBatch batch = new JsonRpcResponseBatch(envelope, this.http, exchange, body -> {
            int status = body.length == 0 ? 204 : 200;
            return this.reply(exchange, status, this.jsonHeaders(), body);
        });
        exchange.completion().onFinal(done -> this.http.execute(() -> {
            if (done.getCause() != null) {
                this.cancelBatch(batch, done.getCause());
            }
        }));
        this.dispatch(batch, request);
    }

    private void dispatch(JsonRpcResponseBatch batch, HttpRequest source) {
        this.batches.add(batch);
        batch.sent().onFinal(done -> this.http.execute(() -> {
            if (done.isCancelled()) {
                this.cancelBatch(batch, new CancellationException("JSON-RPC response write cancelled"));
            } else if (done.getCause() != null) {
                this.cancelBatch(batch, done.getCause());
            } else {
                this.batches.remove(batch);
            }
        }));
        List<JsonRpcCall> calls = batch.envelope().calls();
        for (int index = 0; index < calls.size(); index++) {
            if (batch.sent().getCause() != null) {
                return;
            }
            JsonRpcCall call = calls.get(index);
            RequestPayload request = call.request();
            if (request == null) {
                this.complete(batch, index, call.response());
                continue;
            }

            // 线协议 ID 原样保存在 call 中，内核只使用当前会话分配的 long ID。
            long id = ++this.incomingId;
            request.setRequestID(id);
            if (source != null) {
                this.copyHeaders(source.headers(), request);
                request.addOption("Location", source.uri());
            }
            JsonRpcPendingCall pending = new JsonRpcPendingCall(batch, index);
            this.incoming.put(id, pending);
            request.completion().onFinal(done -> this.http.execute(() -> {
                if (this.incoming.remove(id, pending)) {
                    Throwable cause = done.getCause();
                    if (cause == null) {
                        cause = new CancellationException("RPC completed without a response");
                    }
                    this.complete(batch, pending.index(), this.codec.encodeError(call, cause));
                }
            }));
            try {
                this.messages.accept(request, id);
            } catch (Exception failure) {
                this.incoming.remove(id);
                this.complete(batch, index, this.codec.encodeError(call, failure));
                request.complete(failure);
            }
        }
    }

    private void complete(JsonRpcResponseBatch batch, int index, JsonNode response) {
        if (!batch.complete(index, response)) {
            return;
        }
        try {
            byte[] body = this.codec.encodeBatch(batch.envelope(), batch.responses());
            this.bridge(batch.write(body), batch.sent());
        } catch (Exception failure) {
            batch.sent().failed(failure);
        }
    }

    private void cancelBatch(JsonRpcResponseBatch batch, Throwable failure) {
        if (!this.batches.remove(batch)) {
            return;
        }
        batch.sent().failed(failure);
        if (batch.exchange() != null) {
            batch.exchange().complete(failure);
        }
        for (JsonRpcCall call : batch.envelope().calls()) {
            RequestPayload request = call.request();
            if (request != null) {
                this.incoming.remove(request.getRequestID());
                request.complete(failure);
            }
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
                sent.failed(new IllegalArgumentException("JSON-RPC channel direction does not support this payload"));
            }
        } catch (Exception failure) {
            if (payload instanceof RequestPayload request) {
                this.failRequest(request, failure);
            }
            sent.failed(failure);
        }
        return sent;
    }

    private void request(RequestPayload request, BasicFuture<Void> sent) throws Exception {
        if (request.completion().isDone()) {
            throw new CancellationException("RPC already completed");
        }
        byte[] body = this.codec.encodeRequest(request);
        String path = this.codec.requestPath(request);
        if (this.upgrade && !StringUtils.equals(path, this.config.contextPath())) {
            throw new IllegalArgumentException("WebSocket service context must match its handshake context: " + path);
        }

        // 完成、取消和超时共用内核生命周期；协议层只释放关联，不增加第二个 RPC 定时器。
        long id = request.getRequestID();
        this.outgoing.put(id, request);
        request.completion().onFinal(done -> this.http.execute(() -> this.outgoing.remove(id, request)));
        sent.onCancel(done -> request.complete(new CancellationException("JSON-RPC write cancelled")));
        Future<Void> writing;
        if (this.upgrade) {
            NetworkChannel<WebSocketPayload> socket = this.sockets.get(0);
            writing = socket.write(WebSocketPayload.newText(new String(body, StandardCharsets.UTF_8)));
        } else {
            writing = this.requestHttp(request, path, body);
        }
        writing.onFinal(done -> this.http.execute(() -> {
            if (done.isCancelled() || done.getCause() != null) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new CancellationException("JSON-RPC write cancelled");
                }
                this.failRequest(request, failure);
            } else if (request.isMessage() && !request.completion().isDone() && this.outgoing.remove(id, request)) {
                // JSON-RPC notification 没有线上回执，Accept 只代表完整写出。
                ResponsePayload accepted = new ResponsePayload();
                accepted.setRequestID(id);
                accepted.setSerializeType("Json");
                accepted.setStatus(ProtocolStatus.Accept);
                this.messages.accept(accepted, id);
                request.complete(null);
            }
        }));
        this.bridge(writing, sent);
    }

    private Future<Void> requestHttp(RequestPayload request, String path, byte[] body) {
        Map<String, List<String>> headers = new LinkedHashMap<>(this.jsonHeaders());
        for (String key : request.getOptionKeys()) {
            String value = request.getOption(key);
            if (key != null && value != null) {
                headers.put(key, List.of(value));
            }
        }
        headers.put("Content-Type", List.of("application/json"));
        int version = this.config.integer("httpVersion", 1);
        HttpRequest wire = HttpRequest.newBuffered("POST", path, headers, body, version, Map.of());
        HttpExchange exchange = new HttpExchange(wire);
        request.completion().onFinal(done -> {
            if (done.getCause() != null) {
                exchange.complete(done.getCause());
            }
        });
        exchange.response().onFinal(done -> this.http.execute(() -> {
            if (request.completion().isDone() || !this.outgoing.containsKey(request.getRequestID())) {
                return;
            }
            if (done.getCause() != null) {
                this.failRequest(request, done.getCause());
                return;
            }
            try {
                HttpResponse response = done.getResult();
                if (request.isMessage()) {
                    // 空 HTTP 响应仅维持交换生命周期，不生成第二次应用回执。
                    return;
                }
                if (response.status() != 200) {
                    throw new IOException("JSON-RPC HTTP status " + response.status());
                }
                ResponsePayload result = this.codec.decodeResponse(request, response.body());
                this.copyHeaders(response.headers(), result);
                this.completeRequest(request, result);
            } catch (Exception failure) {
                this.failRequest(request, failure);
            }
        }));
        return this.http.write(exchange);
    }

    private void respond(ResponsePayload response, BasicFuture<Void> sent) throws Exception {
        JsonRpcPendingCall pending = this.incoming.get(response.getRequestID());
        if (pending == null) {
            sent.completed(null);
            return;
        }
        RequestPayload request = pending.call().request();
        if (!request.isMessage() && response.getStatus() == ProtocolStatus.Accept) {
            sent.completed(null);
            return;
        }

        this.incoming.remove(response.getRequestID());
        JsonNode encoded;
        try {
            encoded = this.codec.encodeResponse(pending.call(), response);
        } catch (Exception failure) {
            encoded = this.codec.encodeError(pending.call(), failure);
        }
        this.complete(pending.batch(), pending.index(), encoded);
        this.observe(pending.batch().sent(), sent);
    }

    private void attach(HttpExchange exchange, RouteMount mount) {
        Future<NetworkChannel<WebSocketPayload>> upgraded = exchange.upgraded(WebSocketPayload.class, socket -> {
            return this.receiver(socket, mount);
        });
        this.ready.onCancel(done -> upgraded.cancel());
        upgraded.onFinal(done -> {
            if (done.isCancelled()) {
                this.ready.cancel();
            } else if (done.getCause() != null) {
                this.ready.failed(done.getCause());
            }
        });
    }

    private ChannelListener<WebSocketPayload> receiver(NetworkChannel<WebSocketPayload> socket, RouteMount mount) {
        return new ChannelListener<WebSocketPayload>() {
            @Override
            public void connected() {
                JsonRpcSession.this.sockets.add(socket);
                JsonRpcSession.this.ready.completed(null);
            }

            @Override
            public void receive(WebSocketPayload message) throws Exception {
                JsonRpcSession.this.receiveWebSocket(socket, mount, message);
            }

            @Override
            public void closed(Throwable cause) {
                JsonRpcSession.this.closeWebSocket(socket, cause);
            }
        };
    }

    private void receiveWebSocket(NetworkChannel<WebSocketPayload> socket, RouteMount mount, //
            WebSocketPayload message) throws Exception {
        if (!message.isText()) {
            throw new IOException("JSON-RPC WebSocket requires text messages");
        }
        byte[] bytes = message.bytes();
        if (this.outbound) {
            long id = this.codec.decodeReplyId(bytes);
            RequestPayload request = this.outgoing.get(id);
            if (request != null) {
                try {
                    this.completeRequest(request, this.codec.decodeResponse(request, bytes));
                } catch (Exception failure) {
                    this.failRequest(request, failure);
                }
            }
            return;
        }

        JsonRpcEnvelope envelope = this.codec.decodeRequest(bytes, mount);
        JsonRpcResponseBatch batch = new JsonRpcResponseBatch(envelope, socket, null, body -> {
            if (body.length == 0) {
                BasicFuture<Void> silent = new BasicFuture<>();
                silent.completed(null);
                return silent;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            return socket.write(WebSocketPayload.newText(json));
        });
        this.dispatch(batch, null);
    }

    private void completeRequest(RequestPayload request, ResponsePayload response) {
        long id = request.getRequestID();
        if (this.outgoing.remove(id, request) && !request.completion().isDone()) {
            this.messages.accept(response, id);
            request.complete(null);
        }
    }

    private void failRequest(RequestPayload request, Throwable failure) {
        long id = request.getRequestID();
        if (this.outgoing.remove(id, request) && !request.completion().isDone()) {
            this.messages.accept(new ThrowPayload(failure), id);
            request.complete(failure);
        }
    }

    private void closeWebSocket(NetworkChannel<WebSocketPayload> socket, Throwable cause) {
        Throwable failure = cause == null ? new IOException("JSON-RPC WebSocket closed") : cause;
        this.sockets.remove(socket);
        for (JsonRpcResponseBatch batch : new ArrayList<>(this.batches)) {
            if (batch.channel() == socket) {
                this.cancelBatch(batch, failure);
            }
        }
        if (this.outbound) {
            for (RequestPayload request : new ArrayList<>(this.outgoing.values())) {
                this.failRequest(request, failure);
            }
        }
    }

    @Override
    public void closed(Throwable cause) {
        Throwable failure = cause == null ? new IOException("JSON-RPC channel closed") : cause;
        this.ready.failed(failure);
        for (JsonRpcResponseBatch batch : new ArrayList<>(this.batches)) {
            this.cancelBatch(batch, failure);
        }
        for (RequestPayload request : new ArrayList<>(this.outgoing.values())) {
            this.failRequest(request, failure);
        }
        this.sockets.clear();
    }

    @Override
    public void prepareDrain() {
        IOException failure = new IOException("JSON-RPC connector closing");
        List<JsonRpcPendingCall> unanswered = new ArrayList<>(this.incoming.values());
        this.incoming.clear();
        for (JsonRpcPendingCall pending : unanswered) {
            JsonNode response = this.codec.encodeError(pending.call(), failure);
            this.complete(pending.batch(), pending.index(), response);
            pending.call().request().complete(failure);
        }
        for (NetworkChannel<WebSocketPayload> socket : new ArrayList<>(this.sockets)) {
            socket.drainAndClose();
        }
    }

    private Future<Void> reply(HttpExchange exchange, int status, Map<String, List<String>> headers, byte[] body) {
        HttpResponse response = HttpResponse.newBuffered(status, headers, body, Map.of());
        if (exchange.respond(response)) {
            return this.http.write(exchange);
        }
        BasicFuture<Void> failed = new BasicFuture<>();
        Throwable cause = exchange.completion().getCause();
        failed.failed(cause == null ? new IOException("HTTP exchange already responded") : cause);
        return failed;
    }

    private Map<String, List<String>> jsonHeaders() {
        return Map.of("Content-Type", List.of("application/json; charset=utf-8"));
    }

    private void copyHeaders(Map<String, List<String>> headers, Payload target) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!header.getValue().isEmpty()) {
                target.addOption(header.getKey(), header.getValue().get(0));
            }
        }
    }

    private void bridge(Future<Void> writing, BasicFuture<Void> sent) {
        sent.onCancel(done -> writing.cancel());
        this.observe(writing, sent);
    }

    private void observe(Future<Void> source, BasicFuture<Void> target) {
        source.onFinal(done -> {
            if (done.isCancelled()) {
                target.cancel();
            } else if (done.getCause() == null) {
                target.completed(null);
            } else {
                target.failed(done.getCause());
            }
        });
    }
}
