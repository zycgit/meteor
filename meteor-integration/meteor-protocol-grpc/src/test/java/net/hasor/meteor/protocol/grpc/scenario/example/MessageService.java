/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.hasor.meteor.rpc.MetCaller;

public final class MessageService implements Messages {
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final CountDownLatch producing = new CountDownLatch(1);
    private final AtomicInteger  produced  = new AtomicInteger();

    public CountDownLatch getCancelled() {
        return this.cancelled;
    }

    public CountDownLatch getProducing() {
        return this.producing;
    }

    public AtomicInteger getProduced() {
        return this.produced;
    }

    public Flow.Publisher<Message> list(Message query) {
        // 迭代器只在下游申请额度后生成元素，计数用于观察反压。
        return MetCaller.fromIterator(() -> {
            Stream<Message> messages = IntStream.range(0, query.getCount()) //
                    .mapToObj(index -> {
                        this.produced.incrementAndGet();
                        return Message.of(query.getValue(), index);
                    });
            return messages.iterator();
        });
    }

    public Message collect(Flow.Publisher<Message> messages) {
        // 等输入流消费完毕后才计算单个响应，等待有明确上限。
        AtomicInteger sum = new AtomicInteger();
        try {
            var consuming = MetCaller.consume(messages, value -> sum.addAndGet(value.getCount()));
            consuming.get(5, TimeUnit.SECONDS);
            return Message.of("sum", sum.get());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Flow.Publisher<Message> exchange(Flow.Publisher<Message> messages) {
        return messages;
    }

    public Flow.Publisher<Message> never(Message query) {
        // 不产生结果，只暴露订阅和取消时机，供期限与取消场景使用。
        return subscriber -> {
            this.producing.countDown();
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) {
                }

                public void cancel() {
                    MessageService.this.cancelled.countDown();
                }
            });
        };
    }

    public Flow.Publisher<Message> broken(Message query) {
        // 错误在订阅消费时发生，用于验证流错误的传播。
        return MetCaller.fromIterator(() -> {
            throw new IllegalStateException("source failure 中文");
        });
    }
}
