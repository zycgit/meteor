/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo.interop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 普通业务实现，由各自框架独立发布。 */
public class DubboInteropServiceImpl implements DubboInteropService {
    private final AtomicInteger notices = new AtomicInteger();

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public String combine(String value, int count) {
        return value + ":" + count;
    }

    @Override
    public String overloaded(String value) {
        return "string:" + value;
    }

    @Override
    public String overloaded(int value) {
        return "int:" + value;
    }

    @Override
    public String noArgs() {
        return "empty";
    }

    @Override
    public byte[] binary(byte[] value) {
        return value;
    }

    @Override
    public DubboInteropPerson person(DubboInteropPerson value) {
        return new DubboInteropPerson(value.getName(), value.getAge() + 1);
    }

    @Override
    public List<DubboInteropPerson> people(List<DubboInteropPerson> values) {
        List<DubboInteropPerson> result = new ArrayList<>();
        for (DubboInteropPerson value : values) {
            result.add(this.person(value));
        }
        return result;
    }

    @Override
    public Map<String, Object> properties(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }

    @Override
    public String nullable(String value) {
        return value;
    }

    @Override
    public void nothing() {
    }

    @Override
    public String fail(String value) {
        throw new IllegalArgumentException("dubbo failure " + value);
    }

    @Override
    public String metadata(String value) {
        return value;
    }

    @Override
    public String slow(String value) {
        // 真实业务延迟让调用方观察网络 RPC 超时，而非只等待本地异步任务。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return value;
    }

    @Override
    public void notice(String value) {
        this.notices.incrementAndGet();
    }

    @Override
    public int notices() {
        return this.notices.get();
    }
}