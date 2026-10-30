/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import com.google.protobuf.StringValue;
import org.apache.dubbo.common.stream.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 将官方流观察器的终止事件转为测试对端的有界等待。 */
final class DubboResponseObserver implements StreamObserver<StringValue> {
    private final List<String>   values        = new ArrayList<>();
    private final CountDownLatch completed     = new CountDownLatch(1);
    private final CountDownLatch firstReceived = new CountDownLatch(1);
    private       Throwable      failure;

    @Override
    public void onNext(StringValue value) {
        this.values.add(value.getValue());
        this.firstReceived.countDown();
    }

    @Override
    public void onError(Throwable error) {
        this.failure = error;
        this.completed.countDown();
        this.firstReceived.countDown();
    }

    @Override
    public void onCompleted() {
        this.completed.countDown();
        this.firstReceived.countDown();
    }

    void awaitFirst() throws Throwable {
        // 收到首条真实网络响应后才允许调用方取消，避免取消发生在建流之前。
        if (!this.firstReceived.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("Dubbo stream did not return its first message");
        }
        if (this.failure != null) {
            throw this.failure;
        }
        if (this.values.isEmpty()) {
            throw new IllegalStateException("Dubbo stream completed before its first message");
        }
    }

    List<String> await() throws Throwable {
        // 网络故障不能让隔离 JVM 永久等待，错误也保留给调用方断言。
        if (!this.completed.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("Dubbo stream did not terminate");
        }
        if (this.failure != null) {
            throw this.failure;
        }
        return new ArrayList<>(this.values);
    }
}
