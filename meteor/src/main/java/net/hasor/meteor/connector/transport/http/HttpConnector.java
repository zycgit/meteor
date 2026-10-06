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
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.*;

/**
 * Complete HTTP messages over serial HTTP/1 exchanges. No application protocol is selected here.
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
        HttpServerChannel channel = new HttpServerChannel(this.config, this.transport, (NetChannel) stack.getChannel(), this.transport.nextExecutor());
        ChannelListener<HttpExchange> receiver = factory.create(channel);
        channel.receiver(receiver);
        stack.addLast("http", new HttpServerDuplex());
        stack.addLastDecoder("aggregate", new HttpRequestAggregator(this.config.integer("maxFrameSize", 16 * 1024 * 1024)));
        stack.addFirstDecoder("lifecycle", new ConnectionEvents(channel::connected, channel::terminated, failure -> channel.close()));
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

        stack.getChannel().subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(channel::close);
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

            String path = request.uri().split("\\?", 2)[0];
            String selected = "";
            int length = -1;
            for (Map.Entry<String, NetworkRoute<HttpExchange>> route : routes.entrySet()) {
                String mount = route.getValue().options().get("contextPath");
                if ((path.equals(mount) || path.startsWith(mount.endsWith("/") ? mount : mount + "/")) && mount.length() > length) {
                    selected = route.getKey();
                    length = mount.length();
                }
            }

            return PartitionKey.newKey(selected);
        }, partitions -> {
            partitions.byInitializer(ctx -> {
                branch.apply(PartitionKey.findKey(ctx).getKey()).config(ctx);
            });
        }).build().config(context);
    }

    public Future<Void> connect(InterAddress target, ChannelFactory<HttpExchange> factory) {
        BasicFuture<Void> result = new BasicFuture<>();
        try {
            HttpClientChannel channel = new HttpClientChannel(this.config, this.transport, target, this.transport.nextExecutor(), this::initializeClient);
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
        stack.addLastDecoder("aggregate", new HttpResponseAggregator(this.config.integer("maxFrameSize", 16 * 1024 * 1024)));
        NetChannel socket = (NetChannel) stack.getChannel();
        stack.addLastDecoder("messages", new HttpClientHandler(channel, socket));
        socket.subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.dispatch(() -> {
                    channel.failed(socket, event.getError());
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
