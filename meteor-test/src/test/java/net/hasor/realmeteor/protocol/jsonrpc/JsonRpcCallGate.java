/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;

/** 只阻塞选中的业务调用，让生命周期场景能够明确控制完成时机。 */
final class JsonRpcCallGate extends JsonRpcDemoServiceImpl {
    private final CountDownLatch entered   = new CountDownLatch(1);
    private final CountDownLatch released  = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);

    @Override
    public String echo(String value) {
        this.awaitRelease(value);
        return super.echo(value);
    }

    @Override
    public void notify(String value) {
        this.awaitRelease(value);
        super.notify(value);
    }

    boolean awaitEntered() throws InterruptedException {
        return this.entered.await(5, TimeUnit.SECONDS);
    }

    boolean isCompleted() {
        return this.completed.getCount() == 0;
    }

    void release() {
        this.released.countDown();
    }

    private void awaitRelease(String value) {
        if (!StringUtils.equals("blocked", value)) {
            return;
        }
        this.entered.countDown();
        try {
            if (!this.released.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test invocation was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test invocation interrupted", failure);
        } finally {
            this.completed.countDown();
        }
    }
}
