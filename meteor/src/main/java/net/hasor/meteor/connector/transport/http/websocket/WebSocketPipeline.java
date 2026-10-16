/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.websocket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeCoordinator;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.websocket.*;

/** 在允许升级的协议挂载上配置 upgrade=websocket，与普通 HTTP 共用端口和路由。 */
public final class WebSocketPipeline {
    public void initializeHttp(TransportConfig config, ProtoBuildContext stack, //
            HttpUpgradeCoordinator upgradeCoordinator, ProtoInitializer ordinaryHttp) {
        WebSocketBridgeHandler bridge = new WebSocketBridgeHandler(upgradeCoordinator);
        stack.addLast("upgrade-lifecycle", bridge, bridge);
        ProtoRoutingControl[] control = new ProtoRoutingControl[1];
        ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault("http", branch -> {
            if (upgradeCoordinator.isServer() && upgradeCoordinator.version() != 2) {
                branch.addLast("handshake", serverHandshake(upgradeCoordinator, bridge, control[0]));
            } else if (!upgradeCoordinator.isServer()) {
                WebSocketClientUpgradeRouteDuplex handshake = new WebSocketClientUpgradeRouteDuplex(//
                        control[0], WebSocketVersion.V13, "upgraded");
                branch.addLast("handshake", handshake);
            }
            ordinaryHttp.config(branch);
        }).branchByInitializer("rejected", branch -> {
        }).branchByInitializer("upgraded", branch -> {
            int limit = config.integer("maxFrameSize", 16 * 1024 * 1024);
            branch.addLast("frames", new WebSocketFrameDuplex(WebSocketVersion.V13, limit));
            branch.addLast("messages", new WebSocketMessageDuplex(true, limit));
            branch.addLastDecoder("payloads", new WebSocketMessageHandler(bridge));
        });
        control[0] = routing.control();
        bridge.routing = control[0];
        if (upgradeCoordinator.isServer() && upgradeCoordinator.version() == 2) {
            // 服务端握手位于普通 HTTP 正文分支之前，拒绝响应不依赖未入口的正文路由。
            stack.addLast("handshake", serverHandshake(upgradeCoordinator, bridge, control[0]));
        }
        stack.addLast("http-upgrade", routing.build());
    }

    private static WebSocketServerUpgradeRouteDuplex serverHandshake(//
            HttpUpgradeCoordinator upgradeCoordinator, WebSocketBridgeHandler bridge, ProtoRoutingControl routing) {
        WebSocketHandshakeAuthorizer authorize = (request, reply) -> {
            HttpRequest snapshot = snapshot(request, upgradeCoordinator.version());
            upgradeCoordinator.authorize(request.requestPath(), route -> {
                String mode = route == null ? null : route.options().get("upgrade");
                if (!StringUtils.equalsIgnoreCase("websocket", mode)) {
                    bridge.prepareRejection();
                    reply.reject(HttpStatus.NOT_FOUND);
                    return;
                }
                bridge.request = snapshot;
                reply.accept();
            });
        };
        return new WebSocketServerUpgradeRouteDuplex(routing, WebSocketVersion.V13, "upgraded", authorize);
    }

    private static HttpRequest snapshot(WebSocketHandshakeRequest request, int version) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        HttpHeaders source = request.headers();
        for (String name : source.headerNames()) {
            headers.put(name, List.copyOf(source.getValues(name)));
        }
        String method = version == 2 ? "CONNECT" : "GET";
        return HttpRequest.newBuffered(method, request.requestPath(), headers, new byte[0], version, Map.of());
    }

    private static final class WebSocketBridgeHandler implements ProtoHandler<HttpObject, HttpObject> {
        private final HttpUpgradeCoordinator            upgradeCoordinator;
        private       ProtoRoutingControl               routing;
        private       HttpRequest                       request;
        private       ProtoContext                      output;
        private       ChannelListener<WebSocketPayload> inbound;

        private WebSocketBridgeHandler(HttpUpgradeCoordinator upgradeCoordinator) {
            this.upgradeCoordinator = upgradeCoordinator;
        }

        private void prepareRejection() {
            if (this.upgradeCoordinator.version() == 2) {
                // CONNECT 未进入普通 HTTP 正文分支，拒绝响应经独立透传分支编码。
                this.routing.switchRoute("rejected");
            }
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (!(event.getData() instanceof WebSocketHandshakeEvent handshake)) {
                return true;
            }
            if (handshake.streamId() != this.upgradeCoordinator.streamId() || this.inbound != null) {
                return true;
            }
            // 握手成功事件负责切换下一轮收发分支。
            this.routing.switchRouteNextTick("upgraded");
            HttpRequest opening = this.request;
            if (opening == null) {
                String method = this.upgradeCoordinator.version() == 2 ? "CONNECT" : "GET";
                opening = HttpRequest.newBuffered(method, handshake.requestPath(), Map.of(), //
                        new byte[0], this.upgradeCoordinator.version(), Map.of());
            }
            int status = this.upgradeCoordinator.version() == 2 ? 200 : 101;
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (StringUtils.isNotBlank(handshake.subProtocol())) {
                headers.put("Sec-WebSocket-Protocol", List.of(handshake.subProtocol()));
            }
            HttpResponse response = HttpResponse.newBuffered(status, headers, new byte[0], Map.of());
            this.inbound = this.upgradeCoordinator.open(opening, response, WebSocketPayload.class, this::write);
            return true;
        }

        private Future<?> write(WebSocketPayload payload) {
            ByteBuf bytes = ByteBuf.wrap(payload.bytes());
            WebSocketMessage message;
            if (payload.isText()) {
                message = TextWebSocketMessage.request(bytes);
            } else {
                message = BinaryWebSocketMessage.request(bytes);
            }
            message.streamId(this.upgradeCoordinator.streamId());
            return this.output.sendData(message);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, //
                ProtoRcvQueue<HttpObject> source, ProtoSndQueue<HttpObject> target) {
            while (source.hasMore() && target.hasSlot()) {
                target.offerMessage(source.takeMessage());
            }
            return ProtoStatus.Next;
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable failure, ProtoExceptionHolder holder) {
            if (this.inbound != null) {
                this.inbound.closed(failure);
            }
            this.upgradeCoordinator.fail(failure);
            return ProtoStatus.Next;
        }

        @Override
        public void onClose(ProtoContext context) {
            if (this.inbound != null) {
                this.inbound.closed(new IOException("WebSocket transport closed"));
            }
        }
    }

    private static final class WebSocketMessageHandler implements ProtoHandler<WebSocketMessage, Object> {
        private final WebSocketBridgeHandler bridge;

        private WebSocketMessageHandler(WebSocketBridgeHandler bridge) {
            this.bridge = bridge;
        }

        @Override
        public void onInit(String name, int size, ProtoContext context) {
            this.bridge.output = context;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, //
                ProtoRcvQueue<WebSocketMessage> source, ProtoSndQueue<Object> target) throws Exception {
            while (source.hasMore()) {
                WebSocketMessage message = source.takeMessage();
                try {
                    ByteBuf content = message.content();
                    byte[] bytes = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), bytes);
                    WebSocketPayload payload;
                    if (message.type() == WebSocketOpcode.TEXT) {
                        payload = WebSocketPayload.newText(new String(bytes, StandardCharsets.UTF_8));
                    } else {
                        payload = WebSocketPayload.newBinary(bytes);
                    }
                    if (this.bridge.inbound == null) {
                        throw new IOException("WebSocket data preceded its handshake");
                    }
                    this.bridge.inbound.receive(payload);
                } finally {
                    message.release();
                }
            }
            return ProtoStatus.Next;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (event.getData() instanceof PongWebSocketEvent pong) {
                pong.release();
                return false;
            }
            if (event.getData() instanceof WebSocketCloseEvent && this.bridge.inbound != null) {
                this.bridge.inbound.closed(null);
            }
            return true;
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable failure, ProtoExceptionHolder holder) {
            return this.bridge.onError(context, failure, holder);
        }

        @Override
        public void onClose(ProtoContext context) {
            this.bridge.onClose(context);
        }
    }
}
