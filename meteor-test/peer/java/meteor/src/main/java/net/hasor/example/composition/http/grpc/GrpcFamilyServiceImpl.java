/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http.grpc;
import java.util.concurrent.atomic.AtomicInteger;

/** 无协议知识的业务实现，调用计数用于观察跨上下文访问是否执行了业务。 */
public class GrpcFamilyServiceImpl implements GrpcFamilyService {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public GrpcFamilyMessage echo(GrpcFamilyMessage request) {
        this.calls.incrementAndGet();
        return GrpcFamilyMessage.of("family:" + request.getText(), request.getNumber() + 1);
    }

    public int getCalls() {
        return this.calls.get();
    }
}
