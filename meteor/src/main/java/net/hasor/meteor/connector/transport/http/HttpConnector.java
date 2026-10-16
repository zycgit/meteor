/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.Map;
import java.util.function.BiFunction;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.SocketConnector;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
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

/** HTTP 传输组装入口，负责选择 HTTP/1、HTTP/2 与 WebSocket 管线。 */
final class HttpConnector extends SocketConnector<HttpExchange> {
    private final Http1Pipeline     http1;
    private final Http2Pipeline     http2;
    private final WebSocketPipeline webSocket;

    HttpConnector(TransportConfig config, TransportPipeline pipeline, ClassLoader loader) {
        super(config, pipeline, loader, socketConfig(config), false);
        this.webSocket = hasWebSocketRoute(config) ? new WebSocketPipeline() : null;

        BiFunction<HttpUpgradeCoordinator, ProtoInitializer, ProtoInitializer> application = this::decorateApplication;
        this.http1 = new Http1Pipeline(config, this.transport, application, this::requireHttps);
        this.http2 = new Http2Pipeline(config, this.transport, httpScheme(config), this.webSocket != null, application);
    }

    @Override
    protected void initialize(ProtoBuildContext stack, InterAddress target, RouteTable<HttpExchange> routes, String initialMountId, ChannelListenerFactory<HttpExchange> factory) throws Exception {
        this.requireHttps(stack);

        boolean server = !stack.getChannel().isClient();
        if (!server) {
            this.http2.initialize(stack, target, routes, initialMountId, factory, false, null);
            return;
        }

        // 同一监听端口先选择 HTTP 版本，再交给对应的固定管线。
        HttpAggregatorRoute cleartext = new HttpAggregatorRoute();
        HttpAggregatorOverTlsRoute tls = new HttpAggregatorOverTlsRoute();
        ProtoRoutingDataSelector<ByteBuf, ByteBuf> selector = (context, source, targetQueue) -> {
            if (context.context(SslContext.class) != null) {
                return tls.route(context, source, targetQueue);
            }
            return cleartext.route(context, source, targetQueue);
        };

        ProtoHelper.standard().nextDecoder("http-probe", new HttpRouteDecoder()).nextRouteAsStatic("http-version", selector, routing -> {
            routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -> {
                this.http1.initializeServer(branch, routes, factory);
            });
            routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -> {
                this.http2.initialize(branch, target, routes, null, factory, true, routing.control());
            });
            routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -> {
                branch.addLast("http", new HttpServerDuplex());
                branch.addLast("upgrade", new H2CUpgradeServerDuplex(routing.control()));
            });
        }).config(stack);
    }

    @Override
    public Future<Void> connect(InterAddress target, RouteTable<HttpExchange> routes, String initialMountId, ChannelListenerFactory<HttpExchange> factory) {
        if (this.config.integer("httpVersion", 1) == 2) {
            return super.connect(target, routes, initialMountId, factory);
        }
        return this.http1.connect(target, routes, initialMountId, factory);
    }

    @Override
    public void close() {
        try {
            this.http1.close();
        } finally {
            super.close();
        }
    }

    private ProtoInitializer decorateApplication(HttpUpgradeCoordinator coordinator, ProtoInitializer ordinary) {
        if (this.webSocket == null) {
            return ordinary;
        }
        return stack -> this.webSocket.initializeHttp(this.config, stack, coordinator, ordinary);
    }

    private static boolean hasWebSocketRoute(TransportConfig config) {
        for (Map<String, String> route : config.routeOptions().values()) {
            if (StringUtils.equalsIgnoreCase("websocket", route.get("upgrade"))) {
                return true;
            }
        }
        return false;
    }

    private static String httpScheme(TransportConfig config) {
        if (Boolean.parseBoolean(config.option("tlsEnabled", "false"))) {
            return "https";
        }
        return config.option("httpScheme", "http");
    }

    private static TcpSoConfig socketConfig(TransportConfig config) {
        TcpSoConfig socket = new TcpSoConfig();
        socket.setConnectTimeoutMs(config.connectTimeout());
        return socket;
    }

    private void requireHttps(ProtoBuildContext stack) {
        if (StringUtils.equalsIgnoreCase("https", httpScheme(this.config))) {
            stack.addLastDecoder("https-required", new HttpTlsGuardHandler());
        }
    }
}
