/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.protocol.soap.support.SignalService;
import java.util.concurrent.atomic.AtomicReference;

/** 用显式释放信号控制通知完成时机，观察回执与业务完成的先后顺序。 */
public final class BlockingSignalServiceImpl implements SignalService {
    private final CountDownLatch          entered  = new CountDownLatch(1);
    private final CountDownLatch          release  = new CountDownLatch(1);
    private final CountDownLatch          finished = new CountDownLatch(1);
    private final AtomicReference<String> message  = new AtomicReference<>();

    @Override
    public void signal(String message) {
        // 先暴露进入状态，由场景决定何时允许业务继续。
        this.entered.countDown();
        try {
            if (this.release.await(10, TimeUnit.SECONDS)) {
                this.message.set(message);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            // 无论正常释放还是中断，都通知观察方本次执行已结束。
            this.finished.countDown();
        }
    }

    public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
        return this.entered.await(timeout, unit);
    }

    public void release() {
        this.release.countDown();
    }

    public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return this.finished.await(timeout, unit);
    }

    public boolean isFinished() {
        return this.finished.getCount() == 0;
    }

    public String getMessage() {
        return this.message.get();
    }
}
