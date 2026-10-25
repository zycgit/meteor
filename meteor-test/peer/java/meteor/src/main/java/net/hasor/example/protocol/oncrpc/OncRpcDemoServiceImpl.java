/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import java.util.concurrent.atomic.AtomicInteger;

/** 普通 Java 实现；请求分派、并发、期限与取消仍由 Meteor 负责。 */
public class OncRpcDemoServiceImpl implements OncRpcDemoService {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int add(int left, int right) {
        return left * 100 + right;
    }

    @Override
    public OncRpcSample mirror(OncRpcSample value) {
        return value;
    }

    @Override
    public void ping() {
    }

    @Override
    public void crash() {
        throw new IllegalStateException("Business failure becomes ONC RPC SYSTEM_ERR");
    }

    @Override
    public String auth() {
        return "meteor";
    }

    @Override
    public int delay(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return milliseconds;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Business delay interrupted", e);
        }
    }

    @Override
    public int increment(int delta) {
        return this.counter.addAndGet(delta);
    }

    @Override
    public int count() {
        return this.counter.get();
    }

    @Override
    public void reset() {
        this.counter.set(0);
    }

    @Override
    public byte[] bytes(byte[] value) {
        return value;
    }
}
