/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import java.util.concurrent.atomic.AtomicReference;

/** 提供多参数、无参数与无返回值调用的可观察业务实现。 */
public final class DocumentBareServiceImpl implements DocumentBareService {
    private final AtomicReference<String> received = new AtomicReference<>();

    @Override
    public String join(String first, String second) {
        return first + " / " + second;
    }

    @Override
    public String status() {
        return "ready";
    }

    @Override
    public void accept(String message) {
        this.received.set(message);
    }

    public String getReceived() {
        return this.received.get();
    }
}
