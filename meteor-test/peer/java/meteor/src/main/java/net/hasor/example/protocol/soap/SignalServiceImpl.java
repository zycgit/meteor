/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** SignalService 的可观察示例实现。 */
public final class SignalServiceImpl implements SignalService, PlainSignalService {
    private final CountDownLatch          invoked = new CountDownLatch(1);
    private final AtomicReference<String> message = new AtomicReference<>();

    @Override
    public void signal(String message) {
        this.message.set(message);
        this.invoked.countDown();
    }

    public boolean awaitInvocation(long timeout, TimeUnit unit) throws InterruptedException {
        return this.invoked.await(timeout, unit);
    }

    public String getMessage() {
        return this.message.get();
    }
}
