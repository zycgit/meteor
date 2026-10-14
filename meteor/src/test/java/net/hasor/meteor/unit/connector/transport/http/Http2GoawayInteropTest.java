/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelFactory;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.http.HttpConnector;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用独立 Netty HTTP/2 对端，在真实连接上验证传输 SPI 的 GOAWAY 边界。 */
public class Http2GoawayInteropTest {
    @Test(timeout = 15000)
    public void clientGoawayDoesNotDiscardRequestsAlreadyAcceptedByMeteor() throws Exception {
        // Meteor 接收请求，独立 Netty 客户端负责发送 GOAWAY 与观察响应。
        ConnectorConfig config = config(Map.of(), true);
        LinkedBlockingQueue<HttpExchange> requests = new LinkedBlockingQueue<>();
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> acknowledged = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel peer = null;
        try (HttpConnector server = new HttpConnector(config, this.getClass().getClassLoader()); //
             NetworkListen listen = server.bind(config.address(), channel -> new ChannelListener<>() {
                 public void connected() {
                     connected.completed(channel);
                 }

                 public void receive(HttpExchange exchange) {
                     requests.add(exchange);
                 }

                 public void closed(Throwable cause) {
                     closed.completed(null);
                 }
             })) {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel channel) {
                    // Netty 处理 HTTP/2 帧，只记录用于同步 GOAWAY 的 PING 确认。
                    channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<Http2Frame>() {
                        protected void channelRead0(ChannelHandlerContext context, Http2Frame frame) {
                            if (frame instanceof Http2PingFrame ping && ping.ack()) {
                                acknowledged.completed(null);
                            }
                        }
                    });
                }
            });
            ChannelFuture connecting = bootstrap.connect("127.0.0.1", listen.getAddress().getPort());
            peer = connecting.channel();
            connecting.get(3, TimeUnit.SECONDS);
            BasicFuture<Integer> response = new BasicFuture<>();
            Http2StreamChannelBootstrap streams = new Http2StreamChannelBootstrap(peer);
            streams.handler(new SimpleChannelInboundHandler<Http2StreamFrame>() {
                protected void channelRead0(ChannelHandlerContext context, Http2StreamFrame frame) {
                    if (frame instanceof Http2DataFrame data && data.isEndStream()) {
                        response.completed((int) data.content().readByte());
                    }
                }
            });
            Http2StreamChannel stream = streams.open().get(3, TimeUnit.SECONDS);
            Http2Headers headers = new DefaultHttp2Headers() //
                    .method("GET").scheme("http").path("/").authority("localhost");
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true)).get(3, TimeUnit.SECONDS);
            HttpExchange incoming = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 客户端 GOAWAY 的 last-stream-id 指服务端发起的流，不得取消已接收的客户端请求。
            peer.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).get(3, TimeUnit.SECONDS);
            peer.writeAndFlush(new DefaultHttp2PingFrame(42)).get(3, TimeUnit.SECONDS);
            acknowledged.get(3, TimeUnit.SECONDS);
            assertFalse(incoming.completion().isDone());

            // Meteor 完成这条已接收请求后，响应到达 Netty 对端，再关闭排空后的连接。
            incoming.respond(HttpResponse.newBuffered(200, Map.of(), new byte[] { 7 }, Map.of()));
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            channel.write(incoming).get(3, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(7), response.get(3, TimeUnit.SECONDS));
            closed.get(3, TimeUnit.SECONDS);
        } finally {
            try {
                if (peer != null) {
                    peer.close().get(3, TimeUnit.SECONDS);
                }
            } finally {
                group.shutdownGracefully(0, 3, TimeUnit.SECONDS).get(4, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 15000)
    public void gracefulGoawayDrainsOnlyStreamsWithinTheAcceptedBoundary() throws Exception {
        // 在同一连接发送三条请求；独立对端将在 GOAWAY 中只接受前两条。
        ConnectorConfig config = config(Map.of("httpVersion", "2"), false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try (Peer peer = new Peer(); //
             HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader())) {
            client.connect(peer.address(), receiver(connected, closed)).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            List<HttpExchange> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                HttpExchange call = request();
                channel.write(call).get(3, TimeUnit.SECONDS);
                calls.add(call);
            }
            List<Http2StreamChannel> streams = peer.receive(3);
            HttpExchange first = calls.get(0);
            HttpExchange second = calls.get(1);
            HttpExchange rejected = calls.get(2);
            int lastAccepted = streams.get(1).stream().id();

            // 边界之外的第三条明确失败，已接受的两条仍等待各自响应。
            peer.goaway(lastAccepted, Http2Error.NO_ERROR);
            assertFailed(rejected);
            assertFalse(first.completion().isDone());
            assertFalse(second.completion().isDone());

            // 倒序完成已接受的流，只有最后一条完成后连接才关闭。
            peer.reply(streams.get(1), 2);
            HttpResponse secondResponse = second.response().get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 2 }, secondResponse.body());
            assertFalse(closed.isDone());
            peer.reply(streams.get(0), 1);
            HttpResponse firstResponse = first.response().get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1 }, firstResponse.body());
            closed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void gracefulGoawayRejectsCallsSubmittedAfterItIsReceived() throws Exception {
        // 准备两条请求，第二条被 GOAWAY 拒绝后可确认客户端已处理该帧。
        ConnectorConfig config = config(Map.of("httpVersion", "2"), false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try (Peer peer = new Peer(); //
             HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader())) {
            client.connect(peer.address(), receiver(connected, closed)).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            HttpExchange accepted = request();
            channel.write(accepted).get(3, TimeUnit.SECONDS);
            HttpExchange rejected = request();
            channel.write(rejected).get(3, TimeUnit.SECONDS);
            List<Http2StreamChannel> streams = peer.receive(2);
            int lastAccepted = streams.get(0).stream().id();
            peer.goaway(lastAccepted, Http2Error.NO_ERROR);
            assertFailed(rejected);
            assertTrue(channel.isOpen());

            // 连接仍在排空时提交的新请求立即失败，写入和响应都向调用方传播错误。
            HttpExchange afterGoaway = request();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                channel.write(afterGoaway).get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
            assertFailed(afterGoaway);

            // 拒绝新请求不影响此前接受的请求，它完成后连接按 GOAWAY 契约关闭。
            assertFalse(accepted.completion().isDone());
            peer.reply(streams.get(0), 1);
            HttpResponse response = accepted.response().get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1 }, response.body());
            closed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void repeatedGoawayCanReduceTheAcceptedBoundary() throws Exception {
        // 三条请求共用一条连接，第一次 GOAWAY 只排除第三条。
        ConnectorConfig config = config(Map.of("httpVersion", "2"), false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try (Peer peer = new Peer(); //
             HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader())) {
            client.connect(peer.address(), receiver(connected, closed)).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            List<HttpExchange> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                HttpExchange call = request();
                channel.write(call).get(3, TimeUnit.SECONDS);
                calls.add(call);
            }
            List<Http2StreamChannel> streams = peer.receive(3);
            int initialBoundary = streams.get(1).stream().id();
            peer.goaway(initialBoundary, Http2Error.NO_ERROR);
            assertFailed(calls.get(2));

            // 第二次 GOAWAY 收缩边界，原本接受的第二条也必须失败。
            int reducedBoundary = streams.get(0).stream().id();
            peer.goaway(reducedBoundary, Http2Error.NO_ERROR);
            assertFailed(calls.get(1));
            HttpExchange accepted = calls.get(0);
            assertFalse(accepted.completion().isDone());

            // 仍在最终边界内的第一条可完成，随后连接排空关闭。
            peer.reply(streams.get(0), 1);
            HttpResponse response = accepted.response().get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1 }, response.body());
            closed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void errorGoawayFailsEveryPendingExchange() throws Exception {
        // 建立两个尚未返回响应的请求，GOAWAY 的边界包含它们。
        ConnectorConfig config = config(Map.of("httpVersion", "2"), false);
        BasicFuture<NetworkChannel<HttpExchange>> connected = new BasicFuture<>();
        BasicFuture<Void> closed = new BasicFuture<>();
        try (Peer peer = new Peer(); //
             HttpConnector client = new HttpConnector(config, this.getClass().getClassLoader())) {
            client.connect(peer.address(), receiver(connected, closed)).get(3, TimeUnit.SECONDS);
            NetworkChannel<HttpExchange> channel = connected.get(3, TimeUnit.SECONDS);
            List<HttpExchange> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                HttpExchange call = request();
                channel.write(call).get(3, TimeUnit.SECONDS);
                calls.add(call);
            }
            List<Http2StreamChannel> streams = peer.receive(2);
            int lastAccepted = streams.get(1).stream().id();

            // 非零错误码使全部未完成请求失败，不再保留边界内请求等待响应。
            peer.goaway(lastAccepted, Http2Error.INTERNAL_ERROR);
            for (HttpExchange call : calls) {
                assertFailed(call);
            }
            closed.get(3, TimeUnit.SECONDS);
        }
    }

    private static ConnectorConfig config(Map<String, String> options, boolean server) {
        InterAddress local = InterAddress.forBinding("http", "127.0.0.1", 0, "default");
        Map<String, String> values = new HashMap<>(options);
        values.put("listenType", "http");
        ProtocolConfig protocol = new ProtocolConfig("http", "http", "http", Map.of("contextPath", "/"));
        return new ConnectorConfig("http", local, values, List.of(protocol), server);
    }

    private static HttpExchange request() {
        HttpRequest request = HttpRequest.newBuffered("POST", "/echo", Map.of(), new byte[] { 1 }, 2, Map.of());
        return new HttpExchange(request);
    }

    private static ChannelFactory<HttpExchange> receiver(BasicFuture<NetworkChannel<HttpExchange>> connected, //
            BasicFuture<Void> closed) {
        return channel -> new ChannelListener<>() {
            public void connected() {
                connected.completed(channel);
            }

            public void receive(HttpExchange exchange) {
                fail("Responses belong to their exchanges");
            }

            public void closed(Throwable cause) {
                closed.completed(null);
                if (cause != null) {
                    connected.failed(cause);
                }
            }
        };
    }

    private static void assertFailed(HttpExchange exchange) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> {
            exchange.response().get(3, TimeUnit.SECONDS);
        });
        assertTrue(failure.getCause() instanceof IOException);
    }

    private static final class Peer implements AutoCloseable {
        private final    EventLoopGroup                          group    = new NioEventLoopGroup(1);
        private final    LinkedBlockingQueue<Http2StreamChannel> requests = new LinkedBlockingQueue<>();
        private final    Channel                                 listener;
        private volatile Channel                                 channel;
        private volatile Http2FrameCodec                         codec;

        private Peer() throws Exception {
            // 独立 Netty 对端只收集真实流，具体 GOAWAY 边界由当前测试决定。
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(this.group).channel(NioServerSocketChannel.class);
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel channel) {
                    Peer.this.channel = channel;
                    Peer.this.codec = Http2FrameCodecBuilder.forServer().build();
                    channel.pipeline().addLast(Peer.this.codec);
                    ChannelInitializer<Http2StreamChannel> streams = new ChannelInitializer<>() {
                        protected void initChannel(Http2StreamChannel stream) {
                            stream.pipeline().addLast(new SimpleChannelInboundHandler<Http2StreamFrame>() {
                                protected void channelRead0(ChannelHandlerContext context, Http2StreamFrame frame) {
                                    if (frame instanceof Http2DataFrame data && data.isEndStream()) {
                                        Peer.this.requests.add(stream);
                                    }
                                }
                            });
                        }
                    };
                    channel.pipeline().addLast(new Http2MultiplexHandler(streams));
                }
            });

            // 监听建立也使用有界等待，构造失败时释放已经创建的事件循环。
            ChannelFuture binding = bootstrap.bind("127.0.0.1", 0);
            this.listener = binding.channel();
            try {
                binding.get(3, TimeUnit.SECONDS);
            } catch (Exception failure) {
                try {
                    this.listener.close().get(3, TimeUnit.SECONDS);
                } finally {
                    this.group.shutdownGracefully(0, 3, TimeUnit.SECONDS).get(4, TimeUnit.SECONDS);
                }
                throw failure;
            }
        }

        private InterAddress address() {
            InetSocketAddress local = (InetSocketAddress) this.listener.localAddress();
            return new InterAddress("http", "127.0.0.1", local.getPort(), "default");
        }

        private List<Http2StreamChannel> receive(int count) throws Exception {
            List<Http2StreamChannel> streams = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Http2StreamChannel stream = this.requests.poll(3, TimeUnit.SECONDS);
                assertNotNull(stream);
                streams.add(stream);
            }
            return streams;
        }

        private void goaway(int lastStreamId, Http2Error error) throws Exception {
            // 在 Netty 事件循环内构造故障帧，保留真实编码与网络写入。
            ChannelPromise promise = this.channel.newPromise();
            this.channel.eventLoop().execute(() -> {
                ChannelHandlerContext context = this.channel.pipeline().context(this.codec);
                this.codec.goAway(context, lastStreamId, error.code(), Unpooled.EMPTY_BUFFER, promise);
                context.flush();
            });
            promise.get(3, TimeUnit.SECONDS);
        }

        private void reply(Http2StreamChannel stream, int value) throws Exception {
            Http2Headers headers = new DefaultHttp2Headers().status("200");
            stream.write(new DefaultHttp2HeadersFrame(headers));
            ByteBuf body = Unpooled.wrappedBuffer(new byte[] { (byte) value });
            Http2DataFrame data = new DefaultHttp2DataFrame(body, true);
            stream.writeAndFlush(data).get(3, TimeUnit.SECONDS);
        }

        public void close() throws Exception {
            // 即使连接关闭失败，也必须继续回收监听器和事件循环。
            try {
                if (this.channel != null) {
                    this.channel.close().get(3, TimeUnit.SECONDS);
                }
            } finally {
                try {
                    this.listener.close().get(3, TimeUnit.SECONDS);
                } finally {
                    this.group.shutdownGracefully(0, 3, TimeUnit.SECONDS).get(4, TimeUnit.SECONDS);
                }
            }
        }
    }
}
