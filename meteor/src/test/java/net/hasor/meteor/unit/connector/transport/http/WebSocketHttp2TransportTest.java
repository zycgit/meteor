/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.util.ReferenceCountUtil;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolTestAccess;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketHandshakeUtils;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPayload;
import org.junit.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.http.HttpConnectorTestAccess.newConnector;
import static net.hasor.meteor.support.connector.transport.http.HttpTransportPlans.routes;
import static org.junit.Assert.*;

/** 在真实 HTTP/2 连接上验证 WebSocket stream 的准入与排空边界。 */
public class WebSocketHttp2TransportTest {
    @Test(timeout = 20000)
    public void refusingAnUnregisteredUpgradeLeavesTheAcceptedStreamUsable() throws Exception {
        // 服务端只允许一个未结束的 exchange；客户端允许继续提交第二个 stream。
        ConnectorConfig incoming = config(1);
        ConnectorConfig outgoing = config(4);
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<WebSocketPayload> replies = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        try (NetworkConnector<HttpExchange> server = newConnector(transport(incoming), loader); //
             NetworkConnector<HttpExchange> client = newConnector(transport(outgoing), loader); //
             NetworkListen listen = server.bind(incoming.address(), routes(incoming), channel -> echo())) {
            client.connect(listen.getAddress(), routes(outgoing), null, channel -> new ChannelListener<>() {
                public void connected() {
                    connected.completed(channel);
                }

                public void receive(HttpExchange exchange) {
                    throw new AssertionError("Client responses belong to their exchange");
                }

                public void closed(Throwable cause) {
                    connected.failed(cause);
                }
            }).get(5, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> http = connected.get(5, TimeUnit.SECONDS);

            // 第一条 stream 完成真实帧往返，确认它已经占用服务端的容量。
            HttpExchange accepted = WebSocketHandshakeUtils.newExchange("/ws", 2);
            Future<NetworkChannel<WebSocketPayload>> acceptedReady = accepted.upgraded(WebSocketPayload.class, //
                    channel -> receiver(replies));
            http.write(accepted).get(5, TimeUnit.SECONDS);
            NetworkChannel<WebSocketPayload> websocket = acceptedReady.get(5, TimeUnit.SECONDS);
            websocket.write(WebSocketPayload.newText("before-refusal")).get(5, TimeUnit.SECONDS);
            WebSocketPayload before = replies.poll(5, TimeUnit.SECONDS);
            assertNotNull(before);
            assertEquals("before-refusal", before.text());

            // 第二条握手抵达已满的端点，必须收到 REFUSED_STREAM，不能等到应用超时。
            HttpExchange rejected = WebSocketHandshakeUtils.newExchange("/ws", 2);
            rejected.upgraded(WebSocketPayload.class, channel -> receiver(new LinkedBlockingQueue<>()));
            http.write(rejected);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                rejected.completion().get(5, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals("HTTP/2 stream reset: 7", failure.getCause().getMessage());

            // 拒绝只结束未登记的新 stream，已接受的通道仍能往返且物理连接保持打开。
            assertFalse(accepted.completion().isDone());
            websocket.write(WebSocketPayload.newText("after-refusal")).get(5, TimeUnit.SECONDS);
            WebSocketPayload after = replies.poll(5, TimeUnit.SECONDS);
            assertNotNull(after);
            assertEquals("after-refusal", after.text());
            assertTrue(websocket.isOpen());
            assertTrue(http.isOpen());
        }
    }

    @Test(timeout = 20000)
    public void drainingServerRejectsExtendedConnectWhileFinishingASubmittedResponse() throws Exception {
        // Meteor 同时挂载普通 HTTP 与 WebSocket，独立 Netty 对端直接观察 HTTP/2 帧。
        ConnectorConfig incoming = mixedConfig();
        ClassLoader loader = this.getClass().getClassLoader();
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<HttpExchange> upgrades = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> requestChannel = new BasicFuture<>();
        BasicFuture<Boolean> connectProtocol = new BasicFuture<>();
        ConcurrentMap<Integer, LinkedBlockingQueue<Http2StreamFrame>> frames = new ConcurrentHashMap<>();
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel peer = null;
        try (NetworkConnector<HttpExchange> server = newConnector(transport(incoming), loader); //
             NetworkListen listen = server.bind(incoming.address(), routes(incoming), channel -> {
                 String plainMountId = incoming.protocol("http").mountId();
                 return mixed(channel, plainMountId, requestChannel, requests, upgrades);
             })) {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel channel) {
                    channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext context, Object value) {
                            if (value instanceof Http2SettingsFrame settings) {
                                Long enabled = settings.settings().get((char) 8);
                                if (enabled != null) {
                                    connectProtocol.completed(enabled == 1);
                                }
                            }
                            context.fireChannelRead(value);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                            errors.add(failure);
                            connectProtocol.failed(failure);
                            context.close();
                        }
                    });
                    channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                }
            });
            ChannelFuture connecting = bootstrap.connect("127.0.0.1", listen.getAddress().getPort());
            peer = connecting.channel();
            connecting.get(5, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, connectProtocol.get(5, TimeUnit.SECONDS));
            Http2StreamChannelBootstrap streams = new Http2StreamChannelBootstrap(peer);
            streams.handler(new ChannelInitializer<Http2StreamChannel>() {
                @Override
                protected void initChannel(Http2StreamChannel channel) {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext context, Object value) {
                            if (value instanceof Http2StreamFrame frame) {
                                int streamId = frame.stream().id();
                                LinkedBlockingQueue<Http2StreamFrame> received = frames.computeIfAbsent(streamId, //
                                        key -> new LinkedBlockingQueue<>());
                                received.add(frame);
                            } else {
                                ReferenceCountUtil.release(value);
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                            errors.add(failure);
                            context.close();
                        }
                    });
                }
            });
            Http2StreamChannel firstStream = streams.open().get(5, TimeUnit.SECONDS);
            Http2Headers firstHeaders = new DefaultHttp2Headers() //
                    .method("GET").scheme("http").path("/plain").authority("localhost");
            firstStream.writeAndFlush(new DefaultHttp2HeadersFrame(firstHeaders, true)) //
                    .get(5, TimeUnit.SECONDS);
            HttpExchange arrival = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(arrival);
            NetworkChannel<HttpExchange> incomingChannel = requestChannel.get(5, TimeUnit.SECONDS);
            HttpBodyStream body = new HttpBodyStream(1024);
            arrival.respond(HttpResponse.newStreaming(200, Map.of(), body, Map.of()));
            Future<Void> writing = incomingChannel.write(arrival);
            body.output().write(7);
            Http2StreamFrame responseHeaders = takeFrame(frames, firstStream.stream());
            try {
                assertTrue(responseHeaders instanceof Http2HeadersFrame);
                Http2HeadersFrame headers = (Http2HeadersFrame) responseHeaders;
                assertEquals("200", headers.headers().status().toString());
                assertFalse(headers.isEndStream());
            } finally {
                ReferenceCountUtil.release(responseHeaders);
            }
            Http2StreamFrame responseChunk = takeFrame(frames, firstStream.stream());
            try {
                assertTrue(responseChunk instanceof Http2DataFrame);
                Http2DataFrame data = (Http2DataFrame) responseChunk;
                assertFalse(data.isEndStream());
                assertEquals(7, data.content().readUnsignedByte());
            } finally {
                ReferenceCountUtil.release(responseChunk);
            }
            assertFalse(writing.isDone());

            // prepareDrain 完成后再发 extended CONNECT；服务端直接回 404 且不交付应用。
            Future<Void> draining = incomingChannel.drainAndClose();
            CountDownLatch prepared = new CountDownLatch(1);
            incomingChannel.execute(prepared::countDown);
            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            assertFalse(draining.isDone());
            assertFalse("Submitted response failed: " + writing.getCause(), writing.isDone());
            assertTrue("HTTP/2 peer closed: " + errors, peer.isActive());
            Http2StreamChannel rejectedStream = streams.open().get(5, TimeUnit.SECONDS);
            Http2Headers websocket = new DefaultHttp2Headers() //
                    .method("CONNECT").scheme("http").path("/ws").authority("localhost") //
                    .add(":protocol", "websocket").add("sec-websocket-version", "13");
            rejectedStream.writeAndFlush(new DefaultHttp2HeadersFrame(websocket, false)) //
                    .get(5, TimeUnit.SECONDS);
            Http2StreamFrame rejectionHeaders = takeFrame(frames, rejectedStream.stream());
            try {
                assertTrue(rejectionHeaders instanceof Http2HeadersFrame);
                Http2HeadersFrame headers = (Http2HeadersFrame) rejectionHeaders;
                assertEquals("404", headers.headers().status().toString());
                assertFalse(headers.isEndStream());
            } finally {
                ReferenceCountUtil.release(rejectionHeaders);
            }
            Http2StreamFrame rejectionEnd = takeFrame(frames, rejectedStream.stream());
            try {
                assertTrue(rejectionEnd instanceof Http2DataFrame);
                Http2DataFrame data = (Http2DataFrame) rejectionEnd;
                assertTrue(data.isEndStream());
                assertEquals(0, data.content().readableBytes());
            } finally {
                ReferenceCountUtil.release(rejectionEnd);
            }
            assertTrue(requests.isEmpty());
            assertTrue(upgrades.isEmpty());

            // 完成首个 stream 的正文；排空前提交的响应写完后 drain 才关闭连接。
            body.finish(Map.of());
            Http2StreamFrame responseEnd = takeFrame(frames, firstStream.stream());
            try {
                assertTrue(responseEnd instanceof Http2DataFrame);
                Http2DataFrame data = (Http2DataFrame) responseEnd;
                assertTrue(data.isEndStream());
                assertEquals(0, data.content().readableBytes());
            } finally {
                ReferenceCountUtil.release(responseEnd);
            }
            writing.get(5, TimeUnit.SECONDS);
            arrival.completion().get(5, TimeUnit.SECONDS);
            draining.get(5, TimeUnit.SECONDS);
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            try {
                if (peer != null) {
                    peer.close().get(5, TimeUnit.SECONDS);
                }
            } finally {
                releaseFrames(frames);
                group.shutdownGracefully(0, 3, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static RouteReceiver<HttpExchange> echo() {
        return new RouteReceiver<>() {
            public void connected() {
            }

            public void receive(String route, HttpExchange exchange) {
                this.receive(exchange);
            }

            public void receive(HttpExchange exchange) {
                exchange.upgraded(WebSocketPayload.class, channel -> new ChannelListener<WebSocketPayload>() {
                    public void connected() {
                    }

                    public void receive(WebSocketPayload payload) {
                        channel.write(payload);
                    }

                    public void closed(Throwable cause) {
                    }
                });
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static RouteReceiver<HttpExchange> mixed(NetworkChannel<HttpExchange> channel, //
            String plainMountId, //
            BasicFuture<NetworkChannel<HttpExchange>> requestChannel, //
            LinkedBlockingQueue<HttpExchange> requests, //
            LinkedBlockingQueue<HttpExchange> upgrades) {
        return new RouteReceiver<>() {
            public void connected() {
            }

            public void receive(String route, HttpExchange exchange) {
                if (StringUtils.equals(plainMountId, route)) {
                    requestChannel.completed(channel);
                    requests.add(exchange);
                    return;
                }

                upgrades.add(exchange);
                exchange.upgraded(WebSocketPayload.class, channel -> new ChannelListener<WebSocketPayload>() {
                    public void connected() {
                    }

                    public void receive(WebSocketPayload payload) {
                        channel.write(payload);
                    }

                    public void closed(Throwable cause) {
                    }
                });
            }

            public void receive(HttpExchange exchange) {
                throw new AssertionError("HTTP/2 application receive requires a selected route");
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static ChannelListener<WebSocketPayload> receiver(LinkedBlockingQueue<WebSocketPayload> payloads) {
        return new ChannelListener<>() {
            public void connected() {
            }

            public void receive(WebSocketPayload payload) {
                payloads.add(payload);
            }

            public void closed(Throwable cause) {
            }
        };
    }

    private static Http2StreamFrame takeFrame( //
            ConcurrentMap<Integer, LinkedBlockingQueue<Http2StreamFrame>> frames, //
            Http2FrameStream stream) throws InterruptedException {
        LinkedBlockingQueue<Http2StreamFrame> received = frames.computeIfAbsent(stream.id(), //
                key -> new LinkedBlockingQueue<>());
        Http2StreamFrame frame = received.poll(5, TimeUnit.SECONDS);
        assertNotNull("No frame for stream " + stream.id() + ", available streams: " + frames.keySet(), frame);
        return frame;
    }

    private static void releaseFrames(ConcurrentMap<Integer, LinkedBlockingQueue<Http2StreamFrame>> frames) {
        for (LinkedBlockingQueue<Http2StreamFrame> received : frames.values()) {
            Http2StreamFrame frame;
            while ((frame = received.poll()) != null) {
                ReferenceCountUtil.release(frame);
            }
        }
    }

    private static ConnectorConfig config(int capacity) {
        Map<String, String> options = Map.of("httpVersion", "2", //
                "maxPendingRequests", Integer.toString(capacity));
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolTestAccess.config("websocket", "websocket", "websocket", //
                Map.of("contextPath", "/ws", "upgrade", "websocket"));
        return new ConnectorConfig("http", address, options, List.of(protocol), true);
    }

    private static ConnectorConfig mixedConfig() {
        Map<String, String> options = Map.of("httpVersion", "2");
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        ProtocolConfig plain = ProtocolTestAccess.config("http", "http", "http", Map.of("contextPath", "/plain"));
        ProtocolConfig websocket = ProtocolTestAccess.config("websocket", "websocket", "websocket", //
                Map.of("contextPath", "/ws", "upgrade", "websocket"));
        return new ConnectorConfig("http", address, options, List.of(plain, websocket), true);
    }
}
