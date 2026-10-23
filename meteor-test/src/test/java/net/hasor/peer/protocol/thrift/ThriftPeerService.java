/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.thrift.generated.*;

/** 只实现 Apache Thrift 生成接口的原生对端，不加载 Meteor 服务或编解码器。 */
final class ThriftPeerService implements ExtendedInteropService.Iface {
    private final AtomicInteger notifications = new AtomicInteger();

    ThriftPeerService() {
    }

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
        byte[] copy = new byte[value.remaining()];
        value.duplicate().get(copy);
        return ByteBuffer.wrap(copy);
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
        // 普通 void 调用仍由官方 Processor 写回成功结果。
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
