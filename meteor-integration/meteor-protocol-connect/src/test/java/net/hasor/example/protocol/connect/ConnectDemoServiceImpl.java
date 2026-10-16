/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.connect;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.hasor.meteor.rpc.MetCaller;

/** 业务实现不感知 HTTP、Connect 帧或序列化细节。 */
public class ConnectDemoServiceImpl implements ConnectDemoService {
    private final CountDownLatch producing = new CountDownLatch(1);
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final AtomicInteger  calls     = new AtomicInteger();
    private final AtomicInteger  produced  = new AtomicInteger();

    public ConnectMessage echo(ConnectMessage request) {
        this.calls.incrementAndGet();
        return ConnectMessage.of(request.getText(), request.getNumber() + 1);
    }

    public ConnectMessage safeEcho(ConnectMessage request) {
        return this.echo(request);
    }

    public ConnectMessage slow(ConnectMessage request) {
        // 业务等待保持有界，调用期限由框架负责。
        try {
            Thread.sleep(request.getNumber());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return this.echo(request);
    }

    public ConnectMessage fail(ConnectMessage request) {
        throw new IllegalArgumentException("connect failure 中文");
    }

    public Flow.Publisher<ConnectMessage> count(ConnectMessage request) {
        // 只有订阅者给出需求后，迭代器才逐条生成消息。
        return MetCaller.fromIterator(() -> {
            Stream<ConnectMessage> messages = IntStream.range(0, request.getNumber()) //
                    .mapToObj(index -> {
                        this.produced.incrementAndGet();
                        return ConnectMessage.of(request.getText(), index);
                    });
            return messages.iterator();
        });
    }

    public ConnectMessage collect(Flow.Publisher<ConnectMessage> requests) {
        // 请求流正常结束后再返回一次汇总结果。
        AtomicInteger total = new AtomicInteger();
        try {
            var consuming = MetCaller.consume(requests, message -> total.addAndGet(message.getNumber()));
            consuming.get(5, TimeUnit.SECONDS);
            return ConnectMessage.of("sum", total.get());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Flow.Publisher<ConnectMessage> chat(Flow.Publisher<ConnectMessage> requests) {
        return requests;
    }

    public Flow.Publisher<ConnectMessage> never(ConnectMessage request) {
        // 保留订阅直至取消，用于说明超时与关闭如何释放源。
        return subscriber -> {
            this.producing.countDown();
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long count) {
                }

                public void cancel() {
                    ConnectDemoServiceImpl.this.cancelled.countDown();
                }
            });
        };
    }

    public Flow.Publisher<ConnectMessage> broken(ConnectMessage request) {
        return MetCaller.fromIterator(() -> {
            throw new IllegalStateException("connect source failure 中文");
        });
    }

    public CountDownLatch getProducing() {
        return this.producing;
    }

    public CountDownLatch getCancelled() {
        return this.cancelled;
    }

    public int getCalls() {
        return this.calls.get();
    }

    public int getProduced() {
        return this.produced.get();
    }
}
