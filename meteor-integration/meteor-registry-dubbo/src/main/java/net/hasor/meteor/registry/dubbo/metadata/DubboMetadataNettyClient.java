/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import net.hasor.cobble.StringUtils;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.netty4.NettyClient;
import org.apache.dubbo.remoting.transport.netty4.NettyClientHandler;
import org.apache.dubbo.remoting.transport.netty4.NettyCodecAdapter;
import org.apache.dubbo.remoting.transport.netty4.NettyEventLoopFactory;
import org.apache.dubbo.remoting.transport.netty4.ssl.SslContexts;
import org.apache.dubbo.remoting.utils.UrlUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;

/** 保留 Dubbo 3.3.6 的客户端管线，在启用 TLS 时为引擎补齐主机与端口。 */
final class DubboMetadataNettyClient extends NettyClient {
    private static final AttributeKey<SSLSession> SSL_SESSION = AttributeKey.valueOf(Constants.SSL_SESSION_KEY);
    private              SslContext               sslContext;

    DubboMetadataNettyClient(URL url, ChannelHandler listener) throws RemotingException {
        super(url, listener);
    }

    @Override
    protected void initBootstrap(NettyClientHandler listener) {
        // 保留 SDK 的 Socket 配置及借用线程组；不创建或关闭额外的传输资源。
        Bootstrap bootstrap = this.getBootstrap();
        bootstrap.group(this.getEventLoopGroup()) //
                .option(ChannelOption.SO_KEEPALIVE, true) //
                .option(ChannelOption.TCP_NODELAY, true) //
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) //
                .channel(NettyEventLoopFactory.socketChannelClass());
        int timeout = Math.max(Constants.DEFAULT_CONNECT_TIMEOUT, this.getConnectTimeout());
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);

        // 不调用 super.initBootstrap，避免先构建随后被丢弃的旧 SSL 上下文与初始化器。
        URL address = this.getUrl();
        this.sslContext = SslContexts.buildClientSslContext(address);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                DubboMetadataNettyClient.this.initializeChannel(channel, listener, address);
            }
        });
    }

    private void initializeChannel(SocketChannel channel, NettyClientHandler listener, URL address) {
        // SSLEngine 使用真实目标地址，保留 SDK/Netty 的证书链和主机名校验。
        if (this.sslContext != null) {
            SslHandler tls = this.sslContext.newHandler(channel.alloc(), address.getHost(), address.getPort());
            tls.handshakeFuture().addListener(result -> {
                if (result.isSuccess()) {
                    channel.attr(SSL_SESSION).set(tls.engine().getSession());
                } else {
                    channel.close();
                }
            });
            channel.pipeline().addLast("negotiation", tls);
        }

        // 编解码、心跳和业务事件处理继续交给 SDK 原有实现。
        NettyCodecAdapter codec = new NettyCodecAdapter(this.getCodec(), address, this);
        int heartbeat = UrlUtils.getHeartbeat(address);
        channel.pipeline().addLast("decoder", codec.getDecoder()) //
                .addLast("encoder", codec.getEncoder()) //
                .addLast("client-idle-handler", new IdleStateHandler(heartbeat, 0, 0, TimeUnit.MILLISECONDS)) //
                .addLast("handler", listener);

        // 保留官方客户端已有的 SOCKS 代理配置，TLS 仍验证最终目标主机。
        ApplicationModel application = address.getOrDefaultApplicationModel();
        String proxyHost = ConfigurationUtils.getProperty(application, "socksProxyHost");
        boolean localHost = NetUtils.isLocalHost(address.getHost());
        boolean sameHost = StringUtils.equals(NetUtils.getLocalHost(), address.getHost());
        if (proxyHost != null && !localHost && !sameHost) {
            String configuredPort = ConfigurationUtils.getProperty(application, "socksProxyPort", "1080");
            int proxyPort = Integer.parseInt(configuredPort);
            channel.pipeline().addFirst(new Socks5ProxyHandler(new InetSocketAddress(proxyHost, proxyPort)));
        }
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            super.doClose();
        } finally {
            ReferenceCountUtil.release(this.sslContext);
            this.sslContext = null;
        }
    }
}
