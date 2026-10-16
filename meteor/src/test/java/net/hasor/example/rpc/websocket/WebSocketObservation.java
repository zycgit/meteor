/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetExtension;

/** 仅观察示例协议实际收发的应用消息，不把本地提交信号算作远端回执。 */
public final class WebSocketObservation implements MetExtension {
    private final AtomicInteger     sentRequests      = new AtomicInteger();
    private final AtomicInteger     sentResponses     = new AtomicInteger();
    private final AtomicInteger     receivedRequests  = new AtomicInteger();
    private final AtomicInteger     receivedResponses = new AtomicInteger();
    private final BasicFuture<Void> suppressedAccept  = new BasicFuture<>();

    void sent(WebSocketEnvelope envelope) {
        if (StringUtils.equals(envelope.type, "request")) {
            this.sentRequests.incrementAndGet();
        } else if (StringUtils.equals(envelope.type, "response")) {
            this.sentResponses.incrementAndGet();
        }
    }

    void received(WebSocketEnvelope envelope) {
        if (StringUtils.equals(envelope.type, "request")) {
            this.receivedRequests.incrementAndGet();
        } else if (StringUtils.equals(envelope.type, "response")) {
            this.receivedResponses.incrementAndGet();
        }
    }

    void suppressAccept() {
        this.suppressedAccept.completed(null);
    }

    public int sentRequests() {
        return this.sentRequests.get();
    }

    public int sentResponses() {
        return this.sentResponses.get();
    }

    public int receivedRequests() {
        return this.receivedRequests.get();
    }

    public int receivedResponses() {
        return this.receivedResponses.get();
    }

    public Future<Void> suppressedAccept() {
        return this.suppressedAccept;
    }

    @Override
    public <T> T getService(Class<T> type) {
        return type.isInstance(this) ? type.cast(this) : null;
    }

    @Override
    public void beforeStart() {
    }

    @Override
    public void afterStart() {
    }

    @Override
    public void close() {
    }
}
