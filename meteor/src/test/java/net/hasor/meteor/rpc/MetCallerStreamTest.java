/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
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
import org.junit.Test;
import static org.junit.Assert.*;

public class MetCallerStreamTest {
    @Test
    public void demandAccumulatesAndReentrantRequestsDoNotGrowTheStack() throws Exception {
        // 初始无需求，随后两次申请各自增加额度。
        Probe<Integer> probe = new Probe<>();
        MetCaller.fromIterable(List.of(1, 2, 3, 4)).subscribe(probe);
        assertTrue(probe.values.isEmpty());
        probe.subscription.request(2);
        assertEquals(List.of(1, 2), probe.values);
        probe.subscription.request(1);
        assertEquals(List.of(1, 2, 3), probe.values);
        probe.subscription.request(Long.MAX_VALUE);
        assertEquals(List.of(1, 2, 3, 4), probe.values);
        assertTrue(probe.complete);

        // consume 在回调中补充额度，十万条数据也不递归进入 onNext。
        AtomicInteger count = new AtomicInteger();
        Flow.Publisher<Integer> source = MetCaller.fromIterator(() -> IntStream.range(0, 100000).iterator());
        MetCaller.consume(source, value -> count.incrementAndGet()).get(3, TimeUnit.SECONDS);
        assertEquals(100000, count.get());
    }

    @Test
    public void cancellationInvalidDemandAndConsumerFailureCloseTheSource() throws Exception {
        Cursor cancelled = new Cursor();
        Probe<Integer> probe = new Probe<>();
        MetCaller.fromIterator(() -> cancelled).subscribe(probe);
        probe.subscription.request(1);
        probe.subscription.cancel();
        assertTrue(cancelled.closed);
        assertFalse(probe.complete);
        assertNull(probe.failure);
        probe.subscription.request(10);
        assertEquals(List.of(1), probe.values);

        // 业务失败到达 Future，并关闭源；不会吞掉错误等到超时。
        Cursor broken = new Cursor();
        IllegalStateException failure = new IllegalStateException("consumer failed");
        Future<Void> result = MetCaller.consume(MetCaller.fromIterator(() -> broken), value -> {
            throw failure;
        });
        assertSame(failure, result.getCause());
        assertTrue(broken.closed);

        Probe<Integer> invalid = new Probe<>();
        MetCaller.fromIterable(List.of(1)).subscribe(invalid);
        invalid.subscription.request(0);
        assertTrue(invalid.failure instanceof IllegalArgumentException);
        assertTrue(invalid.values.isEmpty());
    }

    @Test
    public void sourceIsLazyAndEachSubscriptionGetsItsOwnIterator() {
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
        Probe<Integer> second = new Probe<>();
        source.subscribe(second);
        second.subscription.request(2);
        assertEquals(List.of(1, 2), second.values);
        assertEquals(1, opened.get());
    }

    @Test
    public void emptyNullAndBrokenSourcesReachATerminalSignal() throws Exception {
        Future<Void> empty = MetCaller.consume(MetCaller.fromIterable(List.of()), value -> fail());
        empty.get(1, TimeUnit.SECONDS);
        Flow.Publisher<Object> nullItem = MetCaller.fromIterator(() -> Arrays.asList((Object) null).iterator());
        assertTrue(MetCaller.consume(nullItem, value -> fail()).getCause() instanceof NullPointerException);
        IllegalStateException failure = new IllegalStateException("source failed");
        Flow.Publisher<Object> broken = MetCaller.fromIterator(() -> {
            throw failure;
        });
        assertSame(failure, MetCaller.consume(broken, value -> fail()).getCause());
    }

    @Test
    public void cancellingBeforeOnSubscribeCancelsTheLateSubscription() {
        AtomicReference<Flow.Subscriber<? super Integer>> receiver = new AtomicReference<>();
        Flow.Publisher<Integer> source = receiver::set;
        Future<Void> completion = MetCaller.consume(source, value -> fail());
        assertTrue(completion.cancel());

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
