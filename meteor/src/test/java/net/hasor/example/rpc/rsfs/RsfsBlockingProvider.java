/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.rsfs;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 用业务执行信号固定消息、取消与断连场景的时序。 */
public final class RsfsBlockingProvider implements RsfsBlockingService {
    private final CountDownLatch entered   = new CountDownLatch(1);
    private final CountDownLatch release   = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);

    public CountDownLatch entered() {
        return this.entered;
    }

    public CountDownLatch completed() {
        return this.completed;
    }

    public void release() {
        this.release.countDown();
    }

    @Override
    public String execute() throws InterruptedException {
        this.awaitRelease();
        return "completed";
    }

    @Override
    public void signal() throws InterruptedException {
        this.awaitRelease();
    }

    private void awaitRelease() throws InterruptedException {
        this.entered.countDown();
        try {
            if (!this.release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Business method was not released");
            }
        } finally {
            this.completed.countDown();
        }
    }
}
