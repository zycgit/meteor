/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

import java.util.concurrent.atomic.AtomicInteger;

/** 组合不同 QName 的参数，并记录是否实际进入业务方法。 */
public class FieldServiceImpl implements FieldService {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String combine(String plain, String qualified) {
        this.invocations.incrementAndGet();
        return plain + ":" + qualified;
    }

    public int getInvocationCount() {
        return this.invocations.get();
    }
}
