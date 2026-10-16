/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
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
import net.hasor.meteor.connector.transport.http.websocket.WebSocketHandshakeUtils;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPayload;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/** test-only JSON RPC 会话，演示公开升级入口与标准 Payload 生命周期的组合。 */
final class JsonWebSocketSession implements ProtocolSession<HttpExchange> {
    private final ProtocolConfig                              config;
    private final boolean                                     outbound;
    private final boolean                                     binary;
    private final NetworkChannel<HttpExchange>                http;
    private final ObjLongConsumer<Payload>                    messages;
    private final WebSocketPayloadCodec                       codec;
    private final WebSocketObservation                        observation;
    private final BasicFuture<Void>                           ready           = new BasicFuture<>();
    private final Map<Long, RequestPayload>                   pending         = new HashMap<>();
    private final Map<Long, NetworkChannel<WebSocketPayload>> requestChannels = new HashMap<>();
    private final List<NetworkChannel<WebSocketPayload>>      channels        = new ArrayList<>();
    private       int                                         nextChannel;

    JsonWebSocketSession(ProtocolConfig config, ProtocolSessionContext context, NetworkChannel<HttpExchange> http, //
            ObjLongConsumer<Payload> messages) {
        this.config = config;
        this.outbound = context.isOutbound();
        this.binary = Boolean.parseBoolean(config.option("binary", "false"));
        this.http = http;
        this.messages = messages;
        this.codec = new WebSocketPayloadCodec(context.metContext(), config);
        this.observation = context.metContext().getExtension(WebSocketObservation.class);
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        if (this.outbound) {
            // H2 可以在同一个 HTTP 会话内打开多个独立 WS stream。
            String path = this.config.option("requestPath", this.config.contextPath());
            int streams = this.config.integer("streams", 1);
            for (int index = 0; index < streams; index++) {
                int httpVersion = this.config.integer("httpVersion", 1);
                HttpExchange exchange = WebSocketHandshakeUtils.newExchange(path, httpVersion);
                this.attach(exchange, null);
                this.http.write(exchange).onFinal(done -> {
                    if (done.getCause() != null) {
                        this.ready.failed(done.getCause());
                    }
                });
            }
        }
    }

    @Override
    public void receive(HttpExchange exchange) {
        // 核心已经选择 HTTP context，应用协议继承该可见性边界。
        RouteMount mount = exchange.request().route().mount();
        this.attach(exchange, mount);
    }

    private void attach(HttpExchange exchange, RouteMount mount) {
        Future<NetworkChannel<WebSocketPayload>> upgraded = exchange.upgraded(WebSocketPayload.class, //
                channel -> this.receiver(channel, mount));
        this.ready.onCancel(done -> upgraded.cancel());
        upgraded.onFinal(done -> {
            if (done.isCancelled()) {
                this.ready.cancel();
            } else if (done.getCause() != null) {
                this.ready.failed(done.getCause());
            }
        });
    }

    private ChannelListener<WebSocketPayload> receiver(NetworkChannel<WebSocketPayload> channel, //
            RouteMount mount) {
        return new ChannelListener<WebSocketPayload>() {
            @Override
            public void connected() {
                JsonWebSocketSession.this.channels.add(channel);
                int expected = JsonWebSocketSession.this.config.integer("streams", 1);
                if (!JsonWebSocketSession.this.outbound || JsonWebSocketSession.this.channels.size() == expected) {
                    JsonWebSocketSession.this.ready.completed(null);
                }
            }

            @Override
            public void receive(WebSocketPayload payload) throws Exception {
                JsonWebSocketSession.this.receivePayload(channel, mount, payload);
            }

            @Override
            public void closed(Throwable cause) {
                JsonWebSocketSession.this.closedChannel(channel, cause);
            }
        };
    }

    private void receivePayload(NetworkChannel<WebSocketPayload> channel, RouteMount mount, //
            WebSocketPayload payload) throws Exception {
        // 每个场景明确选择文本或二进制；错误帧类型不能伪装成成功回显。
        if (payload.isText() == this.binary) {
            throw new IOException("WebSocket message type does not match the selected example protocol");
        }
        WebSocketEnvelope envelope = this.codec.decode(payload.bytes());
        if (this.observation != null) {
            this.observation.received(envelope);
        }
        if (StringUtils.equals(envelope.type, "request") && !this.outbound) {
            RequestPayload request = this.codec.request(envelope, mount);
            if (this.pending.putIfAbsent(envelope.id, request) != null) {
                throw new IOException("Duplicate WebSocket request ID");
            }
            this.requestChannels.put(envelope.id, channel);
            request.completion().onFinal(done -> this.http.execute(() -> {
                this.pending.remove(envelope.id, request);
                this.requestChannels.remove(envelope.id);
            }));
            this.messages.accept(request, envelope.id);
        } else if (StringUtils.equals(envelope.type, "response") && this.outbound) {
            RequestPayload request = this.pending.remove(envelope.id);
            this.requestChannels.remove(envelope.id);
            if (request != null) {
                this.messages.accept(this.codec.response(envelope), envelope.id);
                request.complete(null);
            }
        } else if (StringUtils.equals(envelope.type, "cancel") && !this.outbound) {
            RequestPayload request = this.pending.remove(envelope.id);
            this.requestChannels.remove(envelope.id);
            if (request != null) {
                request.complete(new CancellationException("Remote WebSocket RPC cancelled"));
            }
        } else {
            throw new IOException("Unexpected WebSocket RPC envelope");
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        try {
            if (payload instanceof RequestPayload request && this.outbound) {
                NetworkChannel<WebSocketPayload> channel = this.selectChannel();
                this.pending.put(request.getRequestID(), request);
                this.requestChannels.put(request.getRequestID(), channel);
                request.completion().onFinal(done -> this.http.execute(() -> {
                    // 取消只释放一个请求；其他请求继续复用相同 WebSocket。
                    if (this.pending.remove(request.getRequestID(), request) && done.getCause() != null) {
                        this.requestChannels.remove(request.getRequestID());
                        if (Boolean.parseBoolean(this.config.option("closeStreamOnCancel", "false"))) {
                            channel.close();
                            return;
                        }
                        WebSocketEnvelope cancelled = new WebSocketEnvelope();
                        cancelled.type = "cancel";
                        cancelled.id = request.getRequestID();
                        this.write(channel, cancelled);
                    }
                }));
                Future<Void> sent = this.write(channel, this.codec.request(request));
                if (request.isMessage()) {
                    sent.onFinal(done -> this.http.execute(() -> {
                        // 无回执协议的本地 Accept 只表示完整写出，不代表远端接收或执行。
                        if (done.isCancelled() || done.getCause() != null) {
                            return;
                        }
                        if (this.pending.remove(request.getRequestID(), request)) {
                            this.requestChannels.remove(request.getRequestID());
                            ResponsePayload accepted = new ResponsePayload();
                            accepted.setRequestID(request.getRequestID());
                            accepted.setStatus(ProtocolStatus.Accept);
                            accepted.setSerializeType(request.getSerializeType());
                            this.messages.accept(accepted, request.getRequestID());
                            request.complete(null);
                        }
                    }));
                }
                return sent;
            }
            if (payload instanceof ResponsePayload response && !this.outbound) {
                RequestPayload request = this.pending.get(response.getRequestID());
                if (request == null || response.getStatus() == ProtocolStatus.Accept) {
                    // Dispatcher 的内部入队 Accept 不编码成 WebSocket 应用消息。
                    if (request != null && request.isMessage() && this.observation != null) {
                        this.observation.suppressAccept();
                    }
                    BasicFuture<Void> ignored = new BasicFuture<>();
                    ignored.completed(null);
                    return ignored;
                }
                this.pending.remove(response.getRequestID());
                NetworkChannel<WebSocketPayload> channel = this.requestChannels.remove(response.getRequestID());
                return this.write(channel, this.codec.response(response));
            }
            throw new IllegalArgumentException("Payload direction is not supported by this example protocol");
        } catch (Exception failure) {
            BasicFuture<Void> rejected = new BasicFuture<>();
            rejected.failed(failure);
            return rejected;
        }
    }

    private NetworkChannel<WebSocketPayload> selectChannel() throws IOException {
        for (int index = 0; index < this.channels.size(); index++) {
            NetworkChannel<WebSocketPayload> channel = this.channels.get(this.nextChannel++ % this.channels.size());
            if (channel.isOpen()) {
                return channel;
            }
        }
        throw new IOException("No upgraded WebSocket remains open");
    }

    private Future<Void> write(NetworkChannel<WebSocketPayload> channel, WebSocketEnvelope envelope) {
        try {
            byte[] bytes = this.codec.encode(envelope);
            WebSocketPayload payload;
            if (this.binary) {
                payload = WebSocketPayload.newBinary(bytes);
            } else {
                payload = WebSocketPayload.newText(new String(bytes, StandardCharsets.UTF_8));
            }
            Future<Void> sent = channel.write(payload);
            if (this.observation != null) {
                sent.onFinal(done -> {
                    if (!done.isCancelled() && done.getCause() == null) {
                        this.observation.sent(envelope);
                    }
                });
            }
            return sent;
        } catch (Exception failure) {
            BasicFuture<Void> rejected = new BasicFuture<>();
            rejected.failed(failure);
            return rejected;
        }
    }

    @Override
    public void closed(Throwable cause) {
        Throwable failure = cause == null ? new IOException("WebSocket channel closed") : cause;
        this.ready.failed(failure);
        ArrayList<RequestPayload> closing = new ArrayList<>(this.pending.values());
        this.pending.clear();
        this.requestChannels.clear();
        for (RequestPayload request : closing) {
            if (this.outbound) {
                this.messages.accept(new ThrowPayload(failure), request.getRequestID());
            }
            request.complete(failure);
        }
    }

    private void closedChannel(NetworkChannel<WebSocketPayload> channel, Throwable cause) {
        // 单个 H2 stream 关闭只结束属于它的请求，其他 stream 的关联关系继续保留。
        Throwable failure = cause == null ? new IOException("WebSocket stream closed") : cause;
        this.ready.failed(failure);
        for (RequestPayload request : new ArrayList<>(this.pending.values())) {
            long id = request.getRequestID();
            if (this.requestChannels.get(id) == channel) {
                this.requestChannels.remove(id);
                this.pending.remove(id);
                if (this.outbound) {
                    this.messages.accept(new ThrowPayload(failure), id);
                }
                request.complete(failure);
            }
        }
    }

    @Override
    public void prepareDrain() {
        for (NetworkChannel<WebSocketPayload> channel : this.channels) {
            channel.drainAndClose();
        }
    }
}
