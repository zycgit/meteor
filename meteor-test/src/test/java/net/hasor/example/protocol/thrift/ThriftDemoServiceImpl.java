/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.thrift.generated.Choice;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.Sample;
import net.hasor.example.protocol.thrift.generated.Shade;

/** 业务实现只处理 Java 参数和结果，不使用官方 Processor 执行业务。 */
public class ThriftDemoServiceImpl implements ThriftDemoService {
    private final AtomicInteger notifications = new AtomicInteger();

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int add(int left, int right) {
        return left + right;
    }

    @Override
    public Sample mirror(Sample value) {
        return value.deepCopy();
    }

    @Override
    public List<String> echoList(List<String> value) {
        return new ArrayList<>(value);
    }

    @Override
    public Map<String, Integer> echoMap(Map<String, Integer> value) {
        return new LinkedHashMap<>(value);
    }

    @Override
    public Set<Long> echoSet(Set<Long> value) {
        return new LinkedHashSet<>(value);
    }

    @Override
    public ByteBuffer echoBinary(ByteBuffer value) {
        byte[] bytes = new byte[value.remaining()];
        value.duplicate().get(bytes);
        return ByteBuffer.wrap(bytes);
    }

    @Override
    public Shade echoEnum(Shade value) {
        return value;
    }

    @Override
    public Choice echoUnion(Choice value) {
        return value.deepCopy();
    }

    @Override
    public UUID echoUuid(UUID value) {
        return value;
    }

    @Override
    public Sample defaults() {
        return new Sample();
    }

    @Override
    public void ping() {
        // 普通 void 方法完成后仍返回 RPC 成功结果。
    }

    @Override
    public String fail(String reason) throws DeclaredFailure {
        throw new DeclaredFailure(409, reason);
    }

    @Override
    public String crash(String reason) {
        throw new IllegalStateException(reason);
    }

    @Override
    public void notify(String value) {
        this.notifications.incrementAndGet();
    }

    @Override
    public int notificationCount() {
        return this.notifications.get();
    }

    @Override
    public String extended(String value) {
        return value;
    }
}
