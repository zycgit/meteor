/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import java.util.concurrent.atomic.AtomicInteger;

/** 查询状态并保留可观察的调用次数。 */
public final class SoapStatusServiceImpl implements SoapStatusService {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String status() {
        this.invocations.incrementAndGet();
        return "ready";
    }

    @Override
    public String internalStatus() {
        this.invocations.incrementAndGet();
        return "private";
    }

    public int getInvocations() {
        return this.invocations.get();
    }
}
