/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EchoImpl implements Echo {
    private final AtomicInteger calls = new AtomicInteger();

    public int getCalls() {
        return this.calls.get();
    }

    public Message echo(Message request) {
        this.calls.incrementAndGet();
        return Message.of(request.getValue(), request.getCount() + 1);
    }

    public Message fail(Message request) {
        throw new IllegalArgumentException("business failure 中文");
    }

    public Message slow(Message request) {
        // 模拟执行时间超过调用期限的业务，让场景验证远端超时。
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
        return this.echo(request);
    }
}
