/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpApplicationInitializer;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpMessageUtils;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeCoordinator;
import net.hasor.meteor.connector.transport.http.http1.Http1Pipeline;
import net.hasor.meteor.connector.transport.http.http2.Http2Pipeline;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPipeline;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.http.HttpServerDuplex;
import net.hasor.neta.codec.http.routing.*;
import net.hasor.neta.codec.ssl.SslContext;

/** HTTP 传输端点，负责管线组装并管理可重连的逻辑客户端。 */
final class HttpConnector extends SocketConnector<HttpExchange> {
    private final    Http1Pipeline                     http1;
    private final    Http2Pipeline                     http2;
    private final    WebSocketPipeline                 webSocket;
    private final    Set<NetworkChannel<HttpExchange>> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean                           closed;

    HttpConnector(TransportConfig config, TransportPipeline pipeline, ClassLoader loader) {
        super(config, pipeline, loader, socketConfig(config), false);
        this.webSocket = hasWebSocketRoute(config) ? new WebSocketPipeline() : null;

        HttpApplicationInitializer application = this::initializeApplication;
        this.http1 = new Http1Pipeline(this.transport, application, this::requireHttps);
        this.http2 = new Http2Pipeline(this.transport, this.webSocket != null, application);
    }

    @Override
    protected void initialize(String initial, InterAddress target, ProtoBuildContext context,//
            RouteTable<HttpExchange> routes, ChannelListenerFactory<HttpExchange> factory) throws Exception {
        this.requireHttps(context);

        boolean server = !context.getChannel().isClient();
        if (!server) {
            this.http2.initialize(context, target, routes, initial, factory, null);
            return;
        }

        // 同一监听端口先选择 HTTP 版本，再交给对应的固定管线。
        HttpAggregatorRoute cleartext = new HttpAggregatorRoute();
        HttpAggregatorOverTlsRoute tls = new HttpAggregatorOverTlsRoute();
        ProtoRoutingDataSelector<ByteBuf, ByteBuf> selector = (ctx, source, targetQueue) -> {
            if (ctx.context(SslContext.class) != null) {
                return tls.route(ctx, source, targetQueue);
            } else {
                return cleartext.route(ctx, source, targetQueue);
            }
        };

        ProtoHelper.standard()//
                .nextDecoder("http-probe", new HttpRouteDecoder())//
                .nextRouteAsStatic("http-version", selector, routing -> {
                    routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -> {
                        this.http1.initializeServer(branch, routes, factory);
                    });
                    routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -> {
                        this.http2.initialize(branch, target, routes, null, factory, routing.control());
                    });
                    routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -> {
                        branch.addLast("http", new HttpServerDuplex());
                        branch.addLast("upgrade", new H2CUpgradeServerDuplex(routing.control()));
                    });
                }).config(context);
    }

    @Override
    public Future<Void> connect(String initial, InterAddress target, RouteTable<HttpExchange> routes,  //
            ChannelListenerFactory<HttpExchange> factory) {
        if (this.config.integer("httpVersion", 1) == 2) {
            return super.connect(initial, target, routes, factory);
        } else {
            try {
                ChannelListenerFactory<HttpExchange> tracked = channel -> this.createClientListener(channel, factory);
                return this.http1.connect(target, routes, initial, tracked);
            } catch (RuntimeException | Error failure) {
                BasicFuture<Void> result = new BasicFuture<>();
                result.failed(failure);
                return result;
            }
        }
    }

    private ChannelListener<HttpExchange> createClientListener(NetworkChannel<HttpExchange> channel, ChannelListenerFactory<HttpExchange> factory) throws Exception {
        // Connector 管理逻辑客户端，物理连接的重建由 Channel 自己负责。
        this.clients.add(channel);
        try {
            if (this.closed) {
                throw new IOException("HTTP connector closed");
            }

            ChannelListenerRelay<HttpExchange> relay = new ChannelListenerRelay<>(() -> this.clients.remove(channel));
            relay.attach(factory.createListener(channel));
            return relay;
        } catch (Exception | Error failure) {
            this.clients.remove(channel);
            throw failure;
        }
    }

    @Override
    public void close() {
        this.closed = true;
        try {
            for (NetworkChannel<HttpExchange> channel : this.clients) {
                channel.close();
            }
            this.clients.clear();
        } finally {
            super.close();
        }
    }

    private void initializeApplication(ProtoBuildContext stack, HttpUpgradeCoordinator coordinator, ProtoInitializer ordinary) {
        ProtoInitializer application = ordinary;
        if (this.webSocket != null) {
            application = branch -> this.webSocket.initializeHttp(this.config, branch, coordinator, ordinary);
        }

        try {
            application.config(stack);
        } catch (Exception failure) {
            throw ExceptionUtils.toRuntime(failure);
        }
    }

    private static boolean hasWebSocketRoute(TransportConfig config) {
        for (Map<String, String> route : config.routeOptions().values()) {
            if (StringUtils.equalsIgnoreCase("websocket", route.get("upgrade"))) {
                return true;
            }
        }
        return false;
    }

    private static TcpSoConfig socketConfig(TransportConfig config) {
        TcpSoConfig socket = new TcpSoConfig();
        socket.setConnectTimeoutMs(config.connectTimeout());
        return socket;
    }

    private void requireHttps(ProtoBuildContext stack) {
        if (StringUtils.equalsIgnoreCase("https", HttpMessageUtils.scheme(this.config))) {
            stack.addLastDecoder("https-required", new HttpTlsGuardHandler());
        }
    }
}
