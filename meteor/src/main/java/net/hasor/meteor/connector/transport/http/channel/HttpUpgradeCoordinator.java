/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.neta.channel.NetChannel;

/** 关联 HTTP 握手与升级通道，保持原路由、执行器和资源归属。 */
public final class HttpUpgradeCoordinator {
    private final HttpChannel                         owner;
    private final NetChannel                          socket;
    private final boolean                             server;
    private final int                                 version;
    private final long                                streamId;
    private final Function<HttpRequest, HttpExchange> handoff;
    private final Runnable                            closeAction;
    private final BooleanSupplier                     available;
    private final Consumer<Throwable>                 failureAction;
    private       boolean                             opened;

    public HttpUpgradeCoordinator(HttpChannel owner, NetChannel socket, boolean server, int version, long streamId, //
            Function<HttpRequest, HttpExchange> handoff, Runnable closeAction, //
            BooleanSupplier available, Consumer<Throwable> failureAction) {
        this.owner = owner;
        this.socket = socket;
        this.server = server;
        this.version = version;
        this.streamId = streamId;
        this.handoff = handoff;
        this.closeAction = closeAction;
        this.available = available;
        this.failureAction = failureAction;
    }

    public boolean isServer() {
        return this.server;
    }

    public int version() {
        return this.version;
    }

    public long streamId() {
        return this.streamId;
    }

    /** HTTP/1 在有序执行器上校验响应先后；HTTP/2 按独立 stream 即时读取不可变路由。 */
    public void authorize(String uri, Consumer<RouteSelection> callback) {
        if (this.version == 2) {
            // Neta 的 H2 CONNECT 路由包装器要求授权在本轮收包内结束。
            callback.accept(this.selectRoute(uri));
            return;
        }

        // HTTP/1 等待已登记的响应写完，不能让升级结果越过前序 exchange。
        this.owner.authorizeUpgrade(uri, route -> {
            // Neta 在收包期间持有 channel monitor。取得一次 monitor，确保 authorizer 已返回，
            // 后续 callback 会走 Neta 的异步完成路径，不会与当前收包状态并发收尾。
            synchronized (this.socket) {
                callback.accept(route);
            }
        });
    }

    private RouteSelection selectRoute(String uri) {
        boolean available = this.owner.acceptsUpgrade() && this.available.getAsBoolean();
        return available ? this.owner.route(uri) : null;
    }

    /** 终止失败的握手；客户端挂起的 exchange 由原 HTTP 关闭路径完成。 */
    public void fail(Throwable failure) {
        this.owner.execute(() -> this.failureAction.accept(failure));
    }

    /**
     * 握手成功后接管当前 HTTP 连接或 stream。writer 必须使用当前分支的编码上下文发送。
     * 返回值供编解码器交付已复制、不依赖 Neta 缓冲区的消息和关闭通知。
     */
    public synchronized <M> ChannelListener<M> open(HttpRequest request, HttpResponse response, //
            Class<M> messageType, Function<M, Future<?>> writer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(writer, "writer");
        if (this.opened) {
            throw new IllegalStateException("HTTP exchange was already upgraded");
        }
        this.opened = true;
        HttpUpgradeChannel<M> channel = new HttpUpgradeChannel<>(this.owner, this.socket, messageType, //
                writer, this.closeAction);
        this.owner.execute(() -> {
            try {
                boolean h2Server = this.server && this.version == 2;
                if (!h2Server && !this.owner.acceptsUpgrade()) {
                    throw new IOException("HTTP endpoint is closing");
                }
                // H2 服务端由 handoff 在同一有序执行器上做最终准入，drain 先到时它会发出 RST。
                HttpExchange exchange = this.handoff.apply(request);
                if (exchange == null || exchange.completion().isDone()) {
                    throw new IOException("HTTP upgrade exchange is no longer available");
                }
                this.owner.trackUpgrade(channel);
                exchange.attachUpgrade(channel);
                exchange.respond(response);
                if (this.server) {
                    this.owner.received(exchange);
                }
            } catch (Throwable failure) {
                channel.closed(failure);
                this.failureAction.accept(failure);
            }
        });
        return channel;
    }
}
