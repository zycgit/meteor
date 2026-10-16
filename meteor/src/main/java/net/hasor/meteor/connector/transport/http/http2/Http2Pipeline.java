/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import java.util.concurrent.Executor;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.SocketRuntime;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpApplicationInitializer;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.routing.ProtoRoutingControl;

/** 创建并组装一个 HTTP/2 物理连接及其 stream 管线。 */
public final class Http2Pipeline {
    private final TransportConfig            config;
    private final SocketRuntime              transport;
    private final String                     scheme;
    private final boolean                    upgrades;
    private final HttpApplicationInitializer application;

    public Http2Pipeline(TransportConfig config, SocketRuntime transport, String scheme, boolean upgrades,
            HttpApplicationInitializer application) {
        this.config = config;
        this.transport = transport;
        this.scheme = scheme;
        this.upgrades = upgrades;
        this.application = application;
    }

    public void initialize(ProtoBuildContext stack, InterAddress target, RouteTable<HttpExchange> routes, //
            String initialMountId, ChannelListenerFactory<HttpExchange> factory, boolean server,
            ProtoRoutingControl routing) {
        NetChannel socket = (NetChannel) stack.getChannel();
        Executor executor = this.transport.nextExecutor();
        Http2Channel channel = new Http2Channel(this.config, this.transport, socket, target, executor, routes,
                initialMountId, server, this.scheme, this.upgrades, this.application);
        try {
            channel.initialize(stack, factory, routing);
        } catch (Exception failure) {
            throw ExceptionUtils.toRuntime(failure);
        }
    }
}
