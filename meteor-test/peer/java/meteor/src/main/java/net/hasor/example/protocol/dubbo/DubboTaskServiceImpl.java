/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 应用拥有自己的业务协调信号；协议不接触这些对象。 */
public final class DubboTaskServiceImpl implements DubboTaskService {
    private final CountDownLatch entered   = new CountDownLatch(1);
    private final CountDownLatch release   = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);

    @Override
    public String execute(String value) throws InterruptedException {
        this.entered.countDown();
        try {
            if (!this.release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Business task was not released");
            }
            return value;
        } finally {
            this.completed.countDown();
        }
    }

    @Override
    public void notify(String value) throws InterruptedException {
        this.execute(value);
    }

    @Override
    public String echo(String value) {
        return value;
    }

    public CountDownLatch getEntered() {
        return this.entered;
    }

    public CountDownLatch getRelease() {
        return this.release;
    }

    public CountDownLatch getCompleted() {
        return this.completed;
    }
}
