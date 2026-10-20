/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpApplicationInitializer;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpMessageUtils;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeCoordinator;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.codec.http.HttpClientDuplex;
import net.hasor.neta.codec.http.HttpResponseAggregator;
import net.hasor.neta.codec.http.HttpServerDuplex;

/** 组装 HTTP/1 服务端与客户端的处理链和事件入口。 */
public final class Http1Pipeline {
    private final SocketRuntime               transport;
    private final HttpApplicationInitializer  application;
    private final Consumer<ProtoBuildContext> secureTransport;

    public Http1Pipeline(SocketRuntime transport, HttpApplicationInitializer application, //
            Consumer<ProtoBuildContext> secureTransport) {
        this.transport = transport;
        this.application = application;
        this.secureTransport = secureTransport;
    }

    public void initializeServer(ProtoBuildContext stack, RouteTable<HttpExchange> routes, //
            ChannelListenerFactory<HttpExchange> factory) {
        NetChannel socket = (NetChannel) stack.getChannel();
        TransportConfig config = this.transport.config();
        Http1ServerChannel channel = new Http1ServerChannel(config, socket, this.transport.nextExecutor(), routes);

        // HTTP/1 编解码和生命周期由同一个版本入口完成组装。
        stack.addLast("http", new HttpServerDuplex());
        ChannelLifecycleHandler lifecycle = new ChannelLifecycleHandler(//
                () -> channel.activated(factory), channel::terminated, failure -> channel.close());
        stack.addFirstDecoder("lifecycle", lifecycle);

        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(//
                channel, socket, 1, 0, channel::upgraded, socket::closeNow, failure -> socket.closeNow());
        ProtoInitializer ordinary = branch -> {
            Http1InboundBodyHandler inbound = new Http1InboundBodyHandler(channel);
            branch.addLastDecoder("incremental", inbound);
            int limit = config.integer("maxFrameSize", 16 * 1024 * 1024);
            branch.addLastDecoder("aggregate", new Http1RoutedRequestAggregator(limit));
            branch.addLastDecoder("messages", new Http1BufferedRequestHandler());
        };
        this.application.initialize(stack, coordinator, ordinary);

        // Neta 事件只负责复制和转交，业务处理进入通道的串行执行器。
        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());
        socket.subscribe(channel::acceptsInbound, SubscribeMode.SYNC, channel::onInbound);
    }

    public Future<Void> connect(InterAddress target, RouteTable<HttpExchange> routes, String initialProtocol,//
            ChannelListenerFactory<HttpExchange> factory) {
        TransportConfig config = this.transport.config();
        boolean streamingResponses = HttpMessageUtils.streamResponseBody(config, routes, initialProtocol);
        Http1ClientChannel channel = new Http1ClientChannel(this.transport, target, routes, streamingResponses);
        return channel.initialize(factory, stack -> this.initializeClient(stack, channel));
    }

    private void initializeClient(ProtoBuildContext stack, Http1ClientChannel channel) {
        this.secureTransport.accept(stack);
        stack.addLast("http", new HttpClientDuplex());
        NetChannel socket = (NetChannel) stack.getChannel();
        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(//
                channel, socket, 1, 0, request -> channel.upgraded(socket), socket::closeNow, //
                failure -> channel.failUpgrade(socket, failure));

        ProtoInitializer ordinary = branch -> {
            Http1InboundBodyHandler inbound = new Http1InboundBodyHandler(channel);

            branch.addLastDecoder("incremental", inbound);
            int limit = this.transport.config().integer("maxFrameSize", 16 * 1024 * 1024);
            branch.addLastDecoder("aggregate", new HttpResponseAggregator(limit));
            branch.addLastDecoder("messages", new Http1ClientResponseHandler(channel));
        };
        this.application.initialize(stack, coordinator, ordinary);

        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());
        socket.subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, channel::onInbound);
    }
}