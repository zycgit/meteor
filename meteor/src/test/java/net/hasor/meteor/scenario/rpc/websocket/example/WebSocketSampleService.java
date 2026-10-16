/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.websocket.example;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实 Dispatcher 执行业务；闩锁只用于协调取消与关闭场景。 */
public final class WebSocketSampleService implements WebSocketSample {
    private final AtomicInteger  calls     = new AtomicInteger();
    private final CountDownLatch entered   = new CountDownLatch(1);
    private final CountDownLatch released  = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);

    @Override
    public String echo(String value) {
        this.calls.incrementAndGet();
        return "server:" + value;
    }

    @Override
    public byte[] binary(byte[] value) {
        this.calls.incrementAndGet();
        return value.clone();
    }

    @Override
    public String block() throws InterruptedException {
        this.calls.incrementAndGet();
        this.entered.countDown();
        try {
            if (!this.released.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Sample business gate was not released");
            }
            return "released";
        } finally {
            this.completed.countDown();
        }
    }

    @Override
    public void notifyBlocked() throws InterruptedException {
        this.block();
    }

    public int calls() {
        return this.calls.get();
    }

    public CountDownLatch entered() {
        return this.entered;
    }

    public CountDownLatch completed() {
        return this.completed;
    }

    public void release() {
        this.released.countDown();
    }
}
