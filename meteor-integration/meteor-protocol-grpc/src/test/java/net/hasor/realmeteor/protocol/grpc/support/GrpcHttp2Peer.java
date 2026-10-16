/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.support;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import io.grpc.netty.shaded.io.netty.bootstrap.Bootstrap;
import io.grpc.netty.shaded.io.netty.buffer.ByteBuf;
import io.grpc.netty.shaded.io.netty.buffer.Unpooled;
import io.grpc.netty.shaded.io.netty.channel.*;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.SocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.handler.codec.http.*;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslHandler;

/** 官方 Netty 对端直接区分 HTTP/2 响应头、数据和 trailers，不依赖 JDK HTTP 客户端。 */
public final class GrpcHttp2Peer implements AutoCloseable {
    private final EventLoopGroup             events   = new NioEventLoopGroup(1);
    private final ResponseFrames             response = new ResponseFrames();
    private final CompletableFuture<Boolean> upgraded = new CompletableFuture<>();
    private final Channel                    channel;
    private final int                        port;
    private final boolean                    upgrade;

    private GrpcHttp2Peer(int port, boolean upgrade) throws Exception {
        this.port = port;
        this.upgrade = upgrade;
        try {
            // TLS 和 h2c 都交给官方协议实现；测试只观察协商结果和收到的帧。
            SslContext ssl = upgrade ? null : GrpcTlsPeer.clientSsl(null);
            Bootstrap bootstrap = new Bootstrap().group(this.events).channel(NioSocketChannel.class);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel socket) {
                    var builder = new HttpToHttp2ConnectionHandlerBuilder() //
                            .server(false) //
                            .frameListener(GrpcHttp2Peer.this.response);
                    builder.httpScheme(upgrade ? HttpScheme.HTTP : HttpScheme.HTTPS);
                    var http2 = builder.build();
                    if (ssl != null) {
                        // 原始 Netty SSL 管线也显式启用 HTTPS 主机名验证。
                        SslHandler tls = ssl.newHandler(socket.alloc(), "localhost", port);
                        SSLParameters parameters = tls.engine().getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        tls.engine().setSSLParameters(parameters);
                        socket.pipeline().addLast(tls, http2);
                    } else {
                        // Upgrade 编解码器负责发送标准 Upgrade 与 HTTP2-Settings 头。
                        HttpClientCodec http1 = new HttpClientCodec();
                        Http2ClientUpgradeCodec codec = new Http2ClientUpgradeCodec(http2);
                        socket.pipeline().addLast(http1, new HttpClientUpgradeHandler(http1, codec, 65536));
                    }
                    socket.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
                            if (event == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
                                GrpcHttp2Peer.this.upgraded.complete(true);
                            } else if (event == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED) {
                                GrpcHttp2Peer.this.upgraded.complete(false);
                            }
                            super.userEventTriggered(context, event);
                        }

                        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                            GrpcHttp2Peer.this.response.finished.completeExceptionally(failure);
                            GrpcHttp2Peer.this.upgraded.completeExceptionally(failure);
                            context.close();
                        }

                        public void channelInactive(ChannelHandlerContext context) throws Exception {
                            IOException failure = new IOException("HTTP/2 peer closed before final trailers");
                            GrpcHttp2Peer.this.response.finished.completeExceptionally(failure);
                            super.channelInactive(context);
                        }
                    });
                }
            });
            var connecting = bootstrap.connect("127.0.0.1", port);
            connecting.get(5, TimeUnit.SECONDS);
            this.channel = connecting.channel();
            if (!upgrade) {
                this.channel.pipeline().get(SslHandler.class).handshakeFuture().get(5, TimeUnit.SECONDS);
            }
        } catch (Exception failure) {
            this.events.shutdownGracefully(0, 1, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
            throw failure;
        }
    }

    public static GrpcHttp2Peer tls(int port) throws Exception {
        return new GrpcHttp2Peer(port, false);
    }

    public static GrpcHttp2Peer h2cUpgrade(int port) throws Exception {
        return new GrpcHttp2Peer(port, true);
    }

    public void send(byte[] body) throws Exception {
        // TLS 请求由官方编码器转换为 HTTP/2；升级请求本身承载 stream 1 的业务正文。
        String path = "/example.Echo/echo";
        ByteBuf content = Unpooled.wrappedBuffer(body);
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, content);
        request.headers().set(HttpHeaderNames.HOST, "localhost:" + this.port);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        request.headers().set(HttpHeaderNames.TE, "trailers");
        if (!this.upgrade) {
            request.headers().setInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), 1);
        }
        this.channel.writeAndFlush(request).get(5, TimeUnit.SECONDS);
    }

    public void awaitResponse() throws Exception {
        this.response.finished.get(5, TimeUnit.SECONDS);
    }

    public boolean wasUpgraded() throws Exception {
        return this.upgraded.get(5, TimeUnit.SECONDS);
    }

    public String applicationProtocol() {
        return this.channel.pipeline().get(SslHandler.class).applicationProtocol();
    }

    public String status() {
        return this.response.status;
    }

    public String grpcStatus() {
        return this.response.grpcStatus;
    }

    public boolean endedWithTrailers() {
        return this.response.endedWithTrailers;
    }

    public int streamId() {
        return this.response.streamId;
    }

    public byte[] body() {
        return this.response.body.toByteArray();
    }

    public void close() throws Exception {
        try {
            this.channel.close().get(3, TimeUnit.SECONDS);
        } finally {
            this.events.shutdownGracefully(0, 1, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
        }
    }

    private static final class ResponseFrames extends Http2FrameAdapter {
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final ByteArrayOutputStream   body     = new ByteArrayOutputStream();
        private       String                  status;
        private       String                  grpcStatus;
        private       int                     streamId;
        private       boolean                 endedWithTrailers;

        public void onHeadersRead(ChannelHandlerContext context, int stream, Http2Headers headers, //
                int padding, boolean endStream) {
            // 首部必须带 :status；gRPC 结果从独立尾部头读取，不能把头部成功当作 RPC 成功。
            this.streamId = stream;
            if (headers.status() != null) {
                this.status = headers.status().toString();
            } else {
                CharSequence value = headers.get("grpc-status");
                this.grpcStatus = value == null ? null : value.toString();
                this.endedWithTrailers = endStream;
            }
            if (endStream) {
                this.finished.complete(null);
            }
        }

        public void onHeadersRead(ChannelHandlerContext context, int stream, Http2Headers headers, //
                int dependency, short weight, boolean exclusive, int padding, boolean endStream) {
            this.onHeadersRead(context, stream, headers, padding, endStream);
        }

        public int onDataRead(ChannelHandlerContext context, int stream, ByteBuf data, int padding, boolean endStream) {
            byte[] bytes = new byte[data.readableBytes()];
            data.readBytes(bytes);
            this.body.writeBytes(bytes);
            if (endStream) {
                this.finished.complete(null);
            }
            return bytes.length + padding;
        }

        public void onRstStreamRead(ChannelHandlerContext context, int stream, long errorCode) {
            this.finished.completeExceptionally(new IOException("HTTP/2 reset: " + errorCode));
        }
    }
}
