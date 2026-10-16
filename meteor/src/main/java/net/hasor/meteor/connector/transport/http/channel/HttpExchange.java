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
import java.util.concurrent.CancellationException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.NetworkChannel;

/**
 * One HTTP exchange, independent of application request IDs and RPC deadlines.
 */
public final class HttpExchange {
    private final HttpRequest               request;
    private final BasicFuture<HttpResponse> response   = new BasicFuture<>();
    private final BasicFuture<Void>         completion = new BasicFuture<>();
    private       UpgradeRegistration<?>    upgradeRegistration;
    private       HttpUpgradeChannel<?>     upgradeChannel;

    public HttpExchange(HttpRequest request) {
        this.request = request;
        this.trackBody(request.stream());
    }

    public HttpRequest request() {
        return this.request;
    }

    public Future<HttpResponse> response() {
        return this.response;
    }

    public boolean respond(HttpResponse response) {
        if (!this.response.completed(response)) {
            return false;
        } else {
            this.trackBody(response.stream());
            return true;
        }
    }

    public Future<Void> completion() {
        return this.completion;
    }

    /**
     * 登记升级通道接收器。客户端在 write 前调用；服务端从握手 exchange 回调中调用。
     * 握手失败、取消或连接关闭会结束 future；成功后当前连接或 HTTP/2 stream 转为消息通道。
     */
    public synchronized <M> Future<NetworkChannel<M>> upgraded(Class<M> messageType, //
            ChannelListenerFactory<M> factory) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(factory, "factory");
        if (this.upgradeRegistration != null) {
            throw new IllegalStateException("An upgrade receiver is already registered");
        }
        UpgradeRegistration<M> registration = new UpgradeRegistration<>(messageType, factory);
        this.upgradeRegistration = registration;
        registration.ready.onCancel(done -> this.complete(new CancellationException("HTTP upgrade cancelled")));
        if (this.completion.isDone()) {
            registration.ready.failed(new IOException("HTTP exchange has already completed"));
        } else if (this.upgradeChannel != null) {
            registration.attach(this.upgradeChannel);
        }
        return registration.ready;
    }

    synchronized void attachUpgrade(HttpUpgradeChannel<?> channel) {
        if (this.upgradeChannel != null || this.completion.isDone()) {
            throw new IllegalStateException("HTTP exchange cannot be upgraded");
        }
        this.upgradeChannel = channel;
        this.completion.onFinal(done -> {
            if (done.getCause() == null) {
                channel.close();
            } else {
                channel.abort(done.getCause());
            }
        });
        channel.completion().onFinal(done -> this.complete(done.getCause()));
        if (this.upgradeRegistration != null) {
            this.upgradeRegistration.attach(channel);
        }
    }

    void terminated(Throwable cause) {
        HttpUpgradeChannel<?> channel;
        synchronized (this) {
            channel = this.upgradeChannel;
        }
        if (channel != null) {
            channel.closed(cause);
        }
        this.complete(cause);
    }

    public void complete(Throwable cause) {
        if (cause == null) {
            this.completion.completed(null);
        } else {
            this.completion.failed(cause);
            this.response.failed(cause);
        }
        synchronized (this) {
            if (this.upgradeRegistration != null && !this.upgradeRegistration.ready.isDone()) {
                Throwable failure = cause == null ? new IOException("HTTP peer did not upgrade the exchange") : cause;
                this.upgradeRegistration.ready.failed(failure);
            }
        }
    }

    private void trackBody(HttpBodyStream bodyStream) {
        if (bodyStream != null) {
            // 即使尚未入队或尚未建立物理连接，失败也必须释放正文的生产者/消费者。
            this.completion.onFailed(done -> bodyStream.fail(done.getCause()));
            bodyStream.completion().onFailed(done -> this.complete(done.getCause()));
        }
    }

    private static final class UpgradeRegistration<M> {
        private final Class<M>                       messageType;
        private final ChannelListenerFactory<M>      factory;
        private final BasicFuture<NetworkChannel<M>> ready = new BasicFuture<>();

        private UpgradeRegistration(Class<M> messageType, ChannelListenerFactory<M> factory) {
            this.messageType = messageType;
            this.factory = factory;
        }

        @SuppressWarnings("unchecked")
        private void attach(HttpUpgradeChannel<?> channel) {
            if (channel.messageType() != this.messageType) {
                IllegalArgumentException failure = new IllegalArgumentException("Incompatible upgraded message type");
                this.ready.failed(failure);
                channel.abort(failure);
                return;
            }
            ((HttpUpgradeChannel<M>) channel).attach(this.factory, this.ready);
        }
    }
}
