/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class AnnotatedInteropServiceImpl implements AnnotatedInteropService {
    private final AtomicInteger notifications = new AtomicInteger();

    public AnnotatedInteropServiceImpl() {
    }

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int sum(int left, int right) {
        return left + right;
    }

    @Override
    public AnnotatedSample mirror(AnnotatedSample value) {
        return value;
    }

    @Override
    public List<String> echoList(List<String> value) {
        return value;
    }

    @Override
    public Map<String, Integer> echoMap(Map<String, Integer> value) {
        return value;
    }

    @Override
    public Set<Long> echoSet(Set<Long> value) {
        return value;
    }

    @Override
    public byte[] echoBinary(byte[] value) {
        return value;
    }

    @Override
    public AnnotatedShade echoEnum(AnnotatedShade value) {
        return value;
    }

    @Override
    public AnnotatedChoice echoUnion(AnnotatedChoice value) {
        return value;
    }

    @Override
    public UUID echoUuid(UUID value) {
        return value;
    }

    @Override
    public AnnotatedSample defaults() {
        return new AnnotatedSample();
    }

    @Override
    public void ping() {
    }

    @Override
    public String fail(String reason) throws AnnotatedFailure {
        AnnotatedFailure failure = new AnnotatedFailure();
        failure.setCode(409);
        failure.setReason(reason);
        throw failure;
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
    public String renamed(String value) {
        return value;
    }
}
