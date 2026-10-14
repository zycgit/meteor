/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.rpc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetCallerStreamTest {
    @Test
    public void demandAccumulatesAcrossRequests() {
        // 订阅后尚未申请额度，不应交付数据。
        Probe<Integer> probe = new Probe<>();
        MetCaller.fromIterable(List.of(1, 2, 3, 4)).subscribe(probe);
        assertTrue(probe.values.isEmpty());

        // 每次申请增加可交付的额度，数据顺序保持不变。
        probe.subscription.request(2);
        assertEquals(List.of(1, 2), probe.values);
        probe.subscription.request(1);
        assertEquals(List.of(1, 2, 3), probe.values);
        probe.subscription.request(Long.MAX_VALUE);
        assertEquals(List.of(1, 2, 3, 4), probe.values);
        assertTrue(probe.complete);
    }

    @Test
    public void reentrantRequestsDoNotGrowTheStack() throws Exception {
        // 准备十万条数据，以覆盖在消费回调中补充额度的重入路径。
        AtomicInteger count = new AtomicInteger();
        Flow.Publisher<Integer> source = MetCaller.fromIterator(() -> IntStream.range(0, 100000).iterator());

        // 消费过程不递归耗尽栈，并在有界等待内交付全部数据。
        Future<Void> completion = MetCaller.consume(source, value -> count.incrementAndGet());
        completion.get(3, TimeUnit.SECONDS);
        assertEquals(100000, count.get());
    }

    @Test
    public void cancellationClosesTheSourceAndStopsFurtherDelivery() {
        // 先申请一条数据，确保可关闭的迭代器已经打开。
        Cursor cursor = new Cursor();
        Probe<Integer> probe = new Probe<>();
        MetCaller.fromIterator(() -> cursor).subscribe(probe);
        probe.subscription.request(1);

        // 取消时关闭源，不发送完成或失败信号，也不响应后续额度。
        probe.subscription.cancel();
        assertTrue(cursor.closed);
        assertFalse(probe.complete);
        assertNull(probe.failure);
        probe.subscription.request(10);
        assertEquals(List.of(1), probe.values);
    }

    @Test
    public void consumerFailureReachesTheFutureAndClosesTheSource() {
        // 消费者抛出一个已知异常，源迭代器支持关闭。
        Cursor cursor = new Cursor();
        IllegalStateException failure = new IllegalStateException("consumer failed");
        Flow.Publisher<Integer> source = MetCaller.fromIterator(() -> cursor);

        // 业务失败立即到达 Future，并触发源关闭。
        Future<Void> result = MetCaller.consume(source, value -> {
            throw failure;
        });
        assertSame(failure, result.getCause());
        assertTrue(cursor.closed);
    }

    @Test
    public void zeroDemandFailsWithoutDeliveringData() {
        // 订阅一个尚未申请数据的源。
        Probe<Integer> invalid = new Probe<>();
        MetCaller.fromIterable(List.of(1)).subscribe(invalid);

        // 非法额度转换成错误信号，不产生任何数据。
        invalid.subscription.request(0);
        assertTrue(invalid.failure instanceof IllegalArgumentException);
        assertTrue(invalid.values.isEmpty());
    }

    @Test
    public void cancellingBeforeDemandDoesNotOpenTheSource() {
        // 通过工厂计数观察迭代器是否真正创建。
        AtomicInteger opened = new AtomicInteger();
        Flow.Publisher<Integer> source = MetCaller.fromIterator(() -> {
            opened.incrementAndGet();
            return List.of(1, 2).iterator();
        });
        Probe<Integer> first = new Probe<>();
        source.subscribe(first);
        assertEquals(0, opened.get());

        // 没有需求就取消订阅，不应为了清理而提前打开源。
        first.subscription.cancel();
        assertEquals(0, opened.get());
    }

    @Test
    public void cancelledSubscriptionDoesNotPreventLaterSubscriptions() {
        // 准备可重复订阅的源，先取消一个尚未打开迭代器的订阅。
        AtomicInteger opened = new AtomicInteger();
        Flow.Publisher<Integer> source = MetCaller.fromIterator(() -> {
            opened.incrementAndGet();
            return List.of(1, 2).iterator();
        });
        Probe<Integer> first = new Probe<>();
        source.subscribe(first);
        assertEquals(0, opened.get());
        first.subscription.cancel();
        assertEquals(0, opened.get());

        // 取消第一个延迟订阅后，后续订阅仍可独立读取完整数据。
        Probe<Integer> second = new Probe<>();
        source.subscribe(second);
        second.subscription.request(2);
        assertEquals(List.of(1, 2), second.values);
        assertEquals(1, opened.get());
    }

    @Test
    public void emptySourceCompletesWithoutDeliveringData() throws Exception {
        // 空数据源不能调用业务消费者。
        Flow.Publisher<Object> source = MetCaller.fromIterable(List.of());

        // 空源仍发送完成信号，让 Future 在期限内结束。
        Future<Void> completion = MetCaller.consume(source, value -> fail());
        completion.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void nullItemFailsWithoutReachingTheConsumer() {
        // 迭代器产生 Flow 不允许的空消息。
        List<Object> items = Arrays.asList((Object) null);
        Flow.Publisher<Object> source = MetCaller.fromIterator(items::iterator);

        // 空消息转为失败信号，不交给业务消费者。
        Future<Void> completion = MetCaller.consume(source, value -> fail());
        assertTrue(completion.getCause() instanceof NullPointerException);
    }

    @Test
    public void iteratorFactoryFailureReachesTheFuture() {
        // 源工厂在打开迭代器时抛出一个已知异常。
        IllegalStateException failure = new IllegalStateException("source failed");
        Flow.Publisher<Object> source = MetCaller.fromIterator(() -> {
            throw failure;
        });

        // 工厂失败到达 Future，并保留原始异常身份。
        Future<Void> completion = MetCaller.consume(source, value -> fail());
        assertSame(failure, completion.getCause());
    }

    @Test
    public void cancellingBeforeOnSubscribeCancelsTheLateSubscription() {
        // 暂存订阅者，在上游交付订阅之前先取消消费 Future。
        AtomicReference<Flow.Subscriber<? super Integer>> receiver = new AtomicReference<>();
        Flow.Publisher<Integer> source = receiver::set;
        Future<Void> completion = MetCaller.consume(source, value -> fail());
        assertTrue(completion.cancel());

        // 迟到订阅立即被取消，不能再向上游申请数据。
        AtomicInteger requested = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        receiver.get().onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                requested.incrementAndGet();
            }

            public void cancel() {
                cancelled.incrementAndGet();
            }
        });
        assertEquals(0, requested.get());
        assertEquals(1, cancelled.get());
    }

    private static final class Cursor implements Iterator<Integer>, AutoCloseable {
        private int     next;
        private boolean closed;

        public boolean hasNext() {
            return this.next < 10;
        }

        public Integer next() {
            return ++this.next;
        }

        public void close() {
            this.closed = true;
        }
    }

    private static final class Probe<T> implements Flow.Subscriber<T> {
        private final List<T>           values = new ArrayList<>();
        private       Flow.Subscription subscription;
        private       Throwable         failure;
        private       boolean           complete;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        public void onNext(T value) {
            this.values.add(value);
        }

        public void onError(Throwable failure) {
            this.failure = failure;
        }

        public void onComplete() {
            this.complete = true;
        }
    }
}
