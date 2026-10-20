/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpApplicationInitializer;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpMessageUtils;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeCoordinator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.HttpRequestAggregator;
import net.hasor.neta.codec.http.HttpResponseAggregator;
import net.hasor.neta.codec.http.h2.*;

/** 创建并组装一个 HTTP/2 物理连接及其 stream 管线。 */
public final class Http2Pipeline {
    private final SocketRuntime              transport;
    private final boolean                    upgrades;
    private final HttpApplicationInitializer application;

    public Http2Pipeline(SocketRuntime transport, boolean upgrades, HttpApplicationInitializer application) {
        this.transport = transport;
        this.upgrades = upgrades;
        this.application = application;
    }

    public void initialize(ProtoBuildContext stack, InterAddress target, RouteTable<HttpExchange> routes, //
            String initialProtocol, ChannelListenerFactory<HttpExchange> factory, ProtoRoutingControl routing) {
        TransportConfig config = this.transport.config();
        boolean streamingResponses = HttpMessageUtils.streamResponseBody(config, routes, initialProtocol);
        NetChannel socket = (NetChannel) stack.getChannel();
        Http2Channel channel = new Http2Channel(this.transport, socket, target, routes, streamingResponses);
        try {
            this.initialize(stack, channel, factory, routing);
        } catch (Exception failure) {
            throw ExceptionUtils.toRuntime(failure);
        }
    }

    private void initialize(ProtoBuildContext stack, Http2Channel channel, //
            ChannelListenerFactory<HttpExchange> factory, ProtoRoutingControl routing) throws Exception {
        NetChannel socket = (NetChannel) stack.getChannel();
        boolean server = !socket.isClient();
        if (!channel.initialize(factory)) {
            return;
        }

        // 生命周期回调由通道处理，组装层只连接事件入口。
        ChannelLifecycleHandler lifecycle = new ChannelLifecycleHandler(() -> {
            channel.activated(factory, this.upgrades);
        }, channel::disconnected, channel::fail);
        stack.addLastDecoder("lifecycle", lifecycle);

        // 先完成连接级帧处理，再按 HTTP/2 stream 分区处理消息。
        int limit = this.transport.config().integer("maxFrameSize", 16 * 1024 * 1024);
        ProtoHandler<Http2Frame, Http2Frame> settingsReady = (context, source, target) -> {
            return channel.onOutboundFrame(source, target);
        };
        ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplex(server))//
                .nextEncoder("h2-settings-ready", settingsReady)//
                .nextDuplex("h2-message", new Http2ObjectDuplex(server, routing))//
                .nextDecoder("h2-events", channel)//
                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partitions -> {
                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                    partitions.policy(policy).byDefault(context -> {
                        context.addLast("lifecycle", new Http2ObjectStreamManager(partitions.control(), policy));
                    }).byInitializer(context -> {
                        if (!this.upgrades) {
                            this.initializeBody(context, channel, server, limit);
                        } else {
                            long id = Long.parseLong(PartitionKey.findKey(context).getKey());
                            HttpUpgradeCoordinator coordinator = new HttpUpgradeCoordinator(//
                                    channel, socket, 2, id, //
                                    request -> {
                                        return channel.upgraded(id, request);
                                    }, () -> {
                                channel.closeUpgrade(id);
                            }, failure -> {
                                channel.failUpgrade(id, failure);
                            });

                            ProtoInitializer body = branch -> {
                                this.initializeBody(branch, channel, server, limit);
                            };
                            this.application.initialize(context, coordinator, body);
                        }
                    });
                }).config(stack);
        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());

        // 订阅物理连接事件，复制、释放和串行交付由通道完成。
        socket.subscribe(channel::acceptsInbound, SubscribeMode.SYNC, channel::onInbound);
    }

    private void initializeBody(ProtoBuildContext stack, Http2Channel channel, boolean server, int limit) {
        ProtoHelper.standard().nextRouteAsStatic("body-mode", channel::selectBodyMode, routing -> {
            routing.branchByInitializer("stream", branch -> {
                Http2InboundBodyHandler handler = new Http2InboundBodyHandler(channel);
                branch.addLastDecoder("body", handler);
            });
            routing.branchByInitializer("complete", branch -> {
                if (server) {
                    branch.addLastDecoder("aggregate", new HttpRequestAggregator(limit));
                    Http2BufferedRequestHandler handler = new Http2BufferedRequestHandler(channel);
                    branch.addLastDecoder("messages", handler);
                } else {
                    branch.addLastDecoder("aggregate", new HttpResponseAggregator(limit));
                }
            });
        }).config(stack);
    }

}
