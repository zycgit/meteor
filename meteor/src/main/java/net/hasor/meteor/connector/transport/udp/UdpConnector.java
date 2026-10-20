/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.io.IOException;
import java.util.Collections;
import java.util.function.Function;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;

final class UdpConnector extends SocketConnector<byte[]> {
    private static final int                      DTLS_OVERHEAD_RESERVE = 128;
    private final        int                      maxDatagramSize;
    private final        boolean                  dtlsEnabled;
    private final        TransportRuntimeServices runtimeServices;
    private final        int                      retransmitTimeout;
    private final        int                      handshakeTimeout;

    UdpConnector(TransportConfig config, TransportPipeline pipeline, TransportRuntimeServices runtimeServices) {
        super(config, pipeline, runtimeServices.classLoader(), socketConfig(), true);
        this.runtimeServices = runtimeServices;
        this.dtlsEnabled = Boolean.parseBoolean(config.option("tlsEnabled", "false"));
        int packetSize = this.dtlsEnabled ? dtlsPacketSize(config) : 0;
        this.maxDatagramSize = maxDatagramSize(config, this.dtlsEnabled, packetSize);
        this.retransmitTimeout = this.dtlsEnabled ? config.integer("dtlsRetransmitTimeout", 1000) : 0;
        this.handshakeTimeout = this.dtlsEnabled ? config.integer("dtlsHandshakeTimeout", config.connectTimeout()) : 0;
        if (this.dtlsEnabled) {
            this.installSecurity(new DtlsPipeline(config, runtimeServices.classLoader(), packetSize));
        }
    }

    @Override
    public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
            ChannelListenerFactory<byte[]> factory) throws Exception {
        if (address.getPort() == 0) {
            throw new IllegalArgumentException("UDP listen port must be between 1 and 65535");
        }
        return super.bind(address, routes, factory);
    }

    private static UdpSoConfig socketConfig() {
        UdpSoConfig socket = new UdpSoConfig();
        socket.setRcvPacketSize(65536);
        return socket;
    }

    private static int dtlsPacketSize(TransportConfig config) {
        int packetSize = config.integer("dtlsPacketSize", 1200);
        if (packetSize < 512 || packetSize > 65507) {
            throw new IllegalArgumentException("dtlsPacketSize must be between 512 and 65507");
        }
        return packetSize;
    }

    private static int maxDatagramSize(TransportConfig config, boolean dtlsEnabled, int packetSize) {
        // A secure application datagram must fit one DTLS record.
        // This preserves UDP message boundaries after decryption.
        int secureLimit = dtlsEnabled ? packetSize - DTLS_OVERHEAD_RESERVE : 65507;
        int maxDatagramSize = config.integer("maxDatagramSize", secureLimit);
        if (maxDatagramSize < 1 || maxDatagramSize > 65507) {
            throw new IllegalArgumentException("maxDatagramSize must be between 1 and 65507");
        }
        if (dtlsEnabled && maxDatagramSize > secureLimit) {
            String message = "DTLS maxDatagramSize must not exceed " + secureLimit;
            throw new IllegalArgumentException(message);
        }
        return maxDatagramSize;
    }

    protected void initialize(String initial, InterAddress target, ProtoBuildContext context,  //
            RouteTable<byte[]> routes, ChannelListenerFactory<byte[]> factory) throws Exception {
        NetChannel socket = (NetChannel) context.getChannel();
        UdpChannel channel = new UdpChannel(socket, target, this.transport.nextExecutor(), //
                this.maxDatagramSize, this.dtlsEnabled);
        ChannelListenerRelay<byte[]> listenerRelay = new ChannelListenerRelay<>(channel::terminated);

        // 先安装物理连接生命周期，ChannelListenerFactory 此后取得的通道已经能够观察关闭。
        Runnable connected = () -> {
            channel.execute(() -> {
                try {
                    listenerRelay.connected();
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        };

        Runnable closed = () -> {
            channel.execute(() -> listenerRelay.closed(channel.failure()));
        };
        context.addLastDecoder("lifecycle", new ChannelLifecycleHandler(connected, closed, channel::fail));
        if (this.dtlsEnabled) {
            DtlsHandshakeHandler handshake = new DtlsHandshakeHandler(//
                    channel, this.runtimeServices, this.retransmitTimeout, this.handshakeTimeout);
            context.addLastDecoder("dtls-ready", handshake);
        }

        Function<String, ProtoInitializer> branch = route -> {
            return ctx -> ctx.addLastDecoder("messages", new RouteByteHandler(route));
        };

        configureRouting(context, branch, routes, initial);

        // 网络事件只负责投递，业务收包仍在串行执行器中处理。
        socket.subscribe(event -> {
            return event.isInbound() || !event.isSuccess();
        }, SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(() -> channel.fail(event.getError()));
                return;
            }

            RouteMessage<byte[]> message = (RouteMessage<byte[]>) event.getData();
            channel.execute(() -> {
                try {
                    if (message.message().length > this.maxDatagramSize) {
                        throw new IOException("Received datagram exceeds " + this.maxDatagramSize);
                    }
                    listenerRelay.receive(message.protocolName(), message.message());
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        });

        // 所有关闭、路由和收包入口就绪后再把通道交给上层，并在同一执行器中绑定监听器。
        try {
            ChannelListener<byte[]> listener = factory.createListener(channel);
            channel.execute(() -> {
                try {
                    listenerRelay.attach(listener);
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        } catch (Exception | Error failure) {
            listenerRelay.attachmentFailed();
            channel.fail(failure);
            throw failure;
        }
    }

    private static void configureRouting(ProtoBuildContext context, Function<String, ProtoInitializer> branch, RouteTable<byte[]> routes, String initial) {
        // 没有协议路由的底层测试或扩展直接接收原始数据报。
        if (routes.definitions().isEmpty()) {
            branch.apply("").config(context);
            return;
        }

        // 主动连接已经选定协议名，回包必须沿用该协议；监听连接仍按每个数据报探测。
        String s = routes.resolveProtocol(initial);
        if (s != null) {
            branch.apply(s).config(context);
            return;
        }

        ProtoHelper.typed(ByteBuf.class, Object.class).nextPartition("route", (ctx, kind, message) -> {
            if (!ctx.isRcv() || !(message instanceof ByteBuf bytes)) {
                return null;
            } else {
                int length = bytes.readableBytes();
                String route = RouteByteUtils.select(Collections.singletonList(bytes), //
                        routes.definitions(), length, true);
                return PartitionKey.newKey(route);
            }
        }, partitions -> {
            partitions.byInitializer(ctx -> {
                branch.apply(PartitionKey.findKey(ctx).getKey()).config(ctx);
            });
        }).build().config(context);
    }

}
