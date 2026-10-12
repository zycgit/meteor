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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.*;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * HTTP/1 and HTTP/2 complete messages, routed by context path on the same listener.
 */
public final class HttpConnector extends SocketConnector<HttpExchange> {
    private final    Set<HttpClientChannel> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean                closed;

    public HttpConnector(ConnectorConfig config, ClassLoader loader) {
        super(config, loader, socketConfig(config), false);
    }

    private static TcpSoConfig socketConfig(ConnectorConfig config) {
        TcpSoConfig socket = new TcpSoConfig();
        socket.setConnectTimeoutMs(config.connectTimeout());
        return socket;
    }

    protected void initialize(ProtoBuildContext stack, InterAddress target, ChannelFactory<HttpExchange> factory, boolean accepted) throws Exception {
        if (!accepted) {
            this.initializeHttp2(stack, target, factory, false, null);
            return;
        }

        // 同一监听端口接收 HTTP/1 和 HTTP/2，之后仍按 contextPath 选择协议挂载。
        HttpAggregatorRoute cleartext = new HttpAggregatorRoute();
        HttpAggregatorOverTlsRoute tls = new HttpAggregatorOverTlsRoute();
        ProtoRoutingDataSelector<ByteBuf, ByteBuf> selector = (context, source, targetQueue) -> {
            // TLS 必须遵循 ALPN，不能被解密后的 preface 或 Upgrade 请求覆盖。
            if (context.context(SslContext.class) != null) {
                return tls.route(context, source, targetQueue);
            } else {
                return cleartext.route(context, source, targetQueue);
            }
        };

        ProtoHelper.standard().nextDecoder("http-probe", new HttpRouteDecoder()).nextRouteAsStatic("http-version", selector, routing -> {
            routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -> {
                this.initializeHttp1(branch, factory);
            });
            routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -> {
                this.initializeHttp2(branch, target, factory, true, routing.control());
            });
            routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -> {
                branch.addLast("http", new HttpServerDuplex());
                branch.addLast("upgrade", new H2CUpgradeServerDuplex(routing.control()));
            });
        }).config(stack);
    }

    private void initializeHttp2(ProtoBuildContext stack, InterAddress target, ChannelFactory<HttpExchange> factory, boolean accepted, ProtoRoutingControl routing) {
        NetChannel socket = (NetChannel) stack.getChannel();
        Executor executor = this.transport.nextExecutor();
        Http2Channel channel = new Http2Channel(this.config, this.transport, socket, target, executor, accepted);
        try {
            channel.initialize(stack, factory, routing);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize HTTP/2 channel", failure);
        }
    }

    private void initializeHttp1(ProtoBuildContext stack, ChannelFactory<HttpExchange> factory) {
        NetChannel socket = (NetChannel) stack.getChannel();
        Executor executor = this.transport.nextExecutor();
        HttpServerChannel channel = new HttpServerChannel(this.config, this.transport, socket, executor);
        ChannelListener<HttpExchange> receiver;
        try {
            receiver = factory.create(channel);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize HTTP channel", failure);
        }
        channel.receiver(receiver);
        stack.addLast("http", new HttpServerDuplex());

        int maxFrameSize = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
        HttpRequestAggregator aggregate = new HttpRequestAggregator(maxFrameSize);
        stack.addLastDecoder("aggregate", aggregate);
        AtomicBoolean selected = new AtomicBoolean();
        ConnectionEvents lifecycle = new ConnectionEvents(() -> {
            selected.set(true);
            channel.connected();
        }, channel::terminated, failure -> {
            channel.close();
        });
        stack.addFirstDecoder("lifecycle", lifecycle);

        Function<String, ProtoInitializer> branch = route -> {
            return ctx -> {
                ctx.addLastDecoder("messages", new HttpServerHandler(route));
            };
        };

        if (receiver instanceof RoutedReceiver) {
            this.configureRouting(stack, branch, (RoutedReceiver<HttpExchange>) receiver);
        } else {
            branch.apply("").config(stack);
        }

        stack.addLastDecoder("input-close", new InputCloseHandler());
        stack.getChannel().subscribe(event -> selected.get() && (event.isInbound() || !event.isSuccess()), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(channel::close);
                return;
            }

            if (event.getData() instanceof SoInputCloseEvent) {
                channel.execute(channel::inputClosed);
                return;
            }

            HttpInbound request = (HttpInbound) event.getData();
            channel.received(request.request(), request.keepAlive(), request.route());
        });
    }

    private void configureRouting(ProtoBuildContext context, Function<String, ProtoInitializer> branch, RoutedReceiver<HttpExchange> receiver) {
        Map<String, NetworkRoute<HttpExchange>> routes = receiver.routes();
        ProtoHelper.typed(FullHttpRequest.class, Object.class).nextPartition("route", (ctx, kind, message) -> {
            if (!ctx.isRcv() || !(message instanceof FullHttpRequest request)) {
                return null;
            }

            String selected = HttpNetworkChannel.selectRoute(routes, request.uri());
            return PartitionKey.newKey(selected);
        }, p -> p.byInitializer(ctx -> {
            branch.apply(PartitionKey.findKey(ctx).getKey()).config(ctx);
        })).build().config(context);
    }

    public Future<Void> connect(InterAddress target, ChannelFactory<HttpExchange> factory) {
        if (this.config.integer("httpVersion", 1) == 2) {
            return super.connect(target, factory);
        }

        BasicFuture<Void> result = new BasicFuture<>();
        try {
            Executor executor = this.transport.nextExecutor();
            HttpClientChannel channel = new HttpClientChannel(this.config, this.transport, target, executor, this::initializeClient);
            channel.receiver(factory.create(channel));

            this.clients.add(channel);
            channel.closeFuture().onFinal(done -> this.clients.remove(channel));
            if (this.closed) {
                channel.close();
                result.failed(new IOException("HTTP connector closed"));
                return result;
            }

            result.onCancel(done -> {
                channel.close();
            });

            channel.execute(() -> {
                try {
                    channel.manageChannel();
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

    @Override
    public void close() {
        this.closed = true;
        try {
            for (HttpClientChannel channel : this.clients) {
                channel.close();
            }
        } finally {
            super.close();
        }
    }

    private void initializeClient(ProtoBuildContext stack, HttpClientChannel channel) {
        stack.addLast("http", new HttpClientDuplex());
        int maxFrameSize = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
        HttpResponseAggregator aggregate = new HttpResponseAggregator(maxFrameSize);
        stack.addLastDecoder("aggregate", aggregate);

        NetChannel socket = (NetChannel) stack.getChannel();
        stack.addLastDecoder("messages", new HttpClientHandler(channel, socket));
        stack.addLastDecoder("input-close", new InputCloseHandler());
        socket.subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.dispatch(() -> {
                    channel.failed(socket, event.getError());
                    socket.closeNow();
                });
                return;
            }

            if (event.getData() instanceof SoInputCloseEvent) {
                channel.dispatch(() -> {
                    channel.failed(socket, new IOException("HTTP peer closed before response"));
                    socket.closeNow();
                });
                return;
            }

            HttpInboundResponse response = (HttpInboundResponse) event.getData();
            ReadPause pause = socket.pauseRead();
            channel.dispatch(() -> {
                try {
                    channel.receive(socket, response.response(), response.keepAlive());
                } finally {
                    socket.resumeRead(pause);
                }
            });
        });
    }
}
