/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeCoordinator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpClientDuplex;
import net.hasor.neta.codec.http.HttpResponseAggregator;
import net.hasor.neta.codec.http.HttpServerDuplex;

/** 组装 HTTP/1 服务端与客户端管线，并持有延迟创建的客户端通道。 */
public final class Http1Pipeline {
    private final    TransportConfig                                                        config;
    private final    SocketRuntime                                                          transport;
    private final    BiFunction<HttpUpgradeCoordinator, ProtoInitializer, ProtoInitializer> application;
    private final    Consumer<ProtoBuildContext>                                            secureTransport;
    private final    Set<Http1ClientChannel>                                                clientChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean                                                                closed;

    public Http1Pipeline(TransportConfig config, SocketRuntime transport, BiFunction<HttpUpgradeCoordinator, ProtoInitializer, ProtoInitializer> application, Consumer<ProtoBuildContext> secureTransport) {
        this.config = config;
        this.transport = transport;
        this.application = application;
        this.secureTransport = secureTransport;
    }

    public void initializeServer(ProtoBuildContext stack, RouteTable<HttpExchange> routes, ChannelListenerFactory<HttpExchange> factory) {
        NetChannel socket = (NetChannel) stack.getChannel();
        Executor executor = this.transport.nextExecutor();
        Http1ServerChannel channel = new Http1ServerChannel(this.config, this.transport, socket, executor, routes);

        // HTTP/1 编解码和生命周期由同一个版本入口完成组装。
        stack.addLast("http", new HttpServerDuplex());
        AtomicBoolean selected = new AtomicBoolean();
        ChannelLifecycleHandler lifecycle = new ChannelLifecycleHandler(() -> {
            try {
                if (!channel.attach(factory)) {
                    socket.closeNow();
                    return;
                }
                selected.set(true);
                channel.connected();
            } catch (Exception failure) {
                socket.closeNow();
            }
        }, channel::terminated, failure -> channel.close());
        stack.addFirstDecoder("lifecycle", lifecycle);

        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(channel, socket, true, 1, 0, channel::upgraded, socket::closeNow, channel::canUpgrade, failure -> socket.closeNow());
        ProtoInitializer ordinary = branch -> {
            branch.addLastDecoder("incremental", new Http1InboundBodyHandler(channel::execute, channel::selectRoute, channel::streamRequest, socket, () -> false, message -> {
                Http1InboundRequest request = (Http1InboundRequest) message;
                channel.received(request.request(), request.keepAlive());
            }));
            int limit = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
            branch.addLastDecoder("aggregate", new Http1RoutedRequestAggregator(limit));
            branch.addLastDecoder("messages", new Http1BufferedRequestHandler());
        };
        this.configureApplication(stack, coordinator, ordinary);

        // Neta 事件只负责复制和转交，业务处理进入通道的串行执行器。
        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());
        stack.getChannel().subscribe(event -> selected.get() && (event.isInbound() || !event.isSuccess()), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(channel::close);
                return;
            }
            if (event.getData() instanceof SoInputCloseEvent) {
                channel.execute(channel::inputClosed);
                return;
            }

            Http1InboundRequest request = (Http1InboundRequest) event.getData();
            channel.received(request.request(), request.keepAlive());
        });
    }

    public Future<Void> connect(InterAddress target, RouteTable<HttpExchange> routes, String initialMountId, ChannelListenerFactory<HttpExchange> factory) {
        BasicFuture<Void> result = new BasicFuture<>();
        try {
            Executor executor = this.transport.nextExecutor();
            Http1ClientChannel channel = new Http1ClientChannel(this.config, this.transport, target, executor, routes, initialMountId, this::initializeClient);
            channel.attach(factory);

            this.clientChannels.add(channel);
            channel.closedFuture().onFinal(done -> this.clientChannels.remove(channel));
            if (this.closed) {
                channel.close();
                result.failed(new IOException("HTTP connector closed"));
                return result;
            }

            result.onCancel(done -> channel.close());
            channel.execute(() -> {
                try {
                    channel.notifyReady();
                    result.completed(null);
                } catch (Throwable error) {
                    result.failed(error);
                    channel.close();
                }
            });
        } catch (Throwable error) {
            result.failed(error);
        }
        return result;
    }

    public void close() {
        this.closed = true;
        for (Http1ClientChannel channel : this.clientChannels) {
            channel.close();
        }
    }

    private void initializeClient(ProtoBuildContext stack, Http1ClientChannel channel) {
        this.secureTransport.accept(stack);
        stack.addLast("http", new HttpClientDuplex());
        NetChannel socket = (NetChannel) stack.getChannel();
        HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(channel, socket, false, 1, 0, request -> channel.upgraded(socket), socket::closeNow, channel::isOpen, failure -> {
            channel.failed(socket, failure);
            socket.closeNow();
        });

        ProtoInitializer ordinary = branch -> {
            branch.addLastDecoder("incremental", new Http1InboundBodyHandler(channel::execute, uri -> null, route -> false, socket, channel::streamResponse, message -> {
                Http1InboundResponse response = (Http1InboundResponse) message;
                channel.execute(() -> channel.receive(socket, response.response(), response.keepAlive()));
            }));
            int limit = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
            branch.addLastDecoder("aggregate", new HttpResponseAggregator(limit));
            branch.addLastDecoder("messages", new Http1ClientResponseHandler(channel, socket));
        };
        this.configureApplication(stack, coordinator, ordinary);

        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());
        socket.subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(() -> {
                    channel.failed(socket, event.getError());
                    socket.closeNow();
                });
                return;
            }
            if (event.getData() instanceof SoInputCloseEvent) {
                channel.execute(() -> {
                    channel.failed(socket, new IOException("HTTP peer closed before response"));
                    socket.closeNow();
                });
                return;
            }

            Http1InboundResponse response = (Http1InboundResponse) event.getData();
            ReadPause pause = socket.pauseRead();
            channel.execute(() -> {
                try {
                    channel.receive(socket, response.response(), response.keepAlive());
                } finally {
                    socket.resumeRead(pause);
                }
            });
        });
    }

    private void configureApplication(ProtoBuildContext stack, HttpUpgradeCoordinator coordinator, ProtoInitializer ordinary) {
        try {
            this.application.apply(coordinator, ordinary).config(stack);
        } catch (Exception failure) {
            throw ExceptionUtils.toRuntime(failure);
        }
    }
}
